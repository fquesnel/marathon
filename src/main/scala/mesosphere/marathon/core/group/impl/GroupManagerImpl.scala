package mesosphere.marathon
package core.group.impl

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Provider
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.api.{Rejection, RejectionException}
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event.{GroupChangeFailed, GroupChangeSuccess}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository.RepositoryConstants
import mesosphere.marathon.metrics.{Counter, Gauge, Metrics}
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.upgrade.GroupVersioningUtil
import mesosphere.marathon.util.{LockedVar, WorkQueue}

import scala.async.Async._
import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class GroupManagerImpl(
    metrics: Metrics,
    val config: MarathonConf,
    initialRoot: Option[RootGroup],
    groupRepository: GroupRepository,
    deploymentService: Provider[DeploymentService])(implicit eventStream: EventStream, ctx: ExecutionContext) extends GroupManager with StrictLogging {

  /**
    * All updates to root() should go through this workqueue and the maxConcurrent should always be "1"
    * as we don't allow multiple updates to the root at the same time.
    */
  private[this] val serializeUpdates: WorkQueue = WorkQueue(
    "GroupManager",
    maxConcurrent = 1, maxQueueLength = config.internalMaxQueuedRootGroupUpdates())

  /**
    * Lock around the root to guarantee read-after-write consistency,
    * Even though updates go through the workqueue, we want to make sure multiple readers always read
    * the latest version of the root. This could be solved by a @volatile too, but this is more explicit.
    */
  private[this] val root = LockedVar(initialRoot)

  private[this] val deploymentsDismissedMetric: Counter =
    metrics.counter("deployments.dismissed")
  private[this] val rootGroupUpdatesMetric: Gauge =
    metrics.gauge("debug.root-group.updates.active")

  override def rootGroup(): RootGroup =
    root.get() match { // linter:ignore:UseGetOrElseNotPatMatch
      case None =>
        root.update {
          case None =>
            val group = Await.result(groupRepository.root(), config.zkTimeoutDuration)
            registerMetrics()
            Some(group)
          case group =>
            group
        }.get
      case Some(group) => group
    }

  override def rootGroupOption(): Option[RootGroup] = root.get()

  override def versions(id: AbsolutePathId): Source[Timestamp, NotUsed] = {
    groupRepository.rootVersions().mapAsync(RepositoryConstants.maxConcurrency) { version =>
      groupRepository.rootVersion(version)
    }.collect { case Some(g) if g.group(id).isDefined => g.version }
  }

  override def appVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed] = {
    groupRepository.appVersions(id)
  }

  override def appVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Option[AppDefinition]] = {
    groupRepository.appVersion(id, version)
  }

  override def podVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed] = {
    groupRepository.podVersions(id)
  }

  override def podVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Option[PodDefinition]] = {
    groupRepository.podVersion(id, version)
  }

  override def group(id: AbsolutePathId): Option[Group] = rootGroup().group(id)

  override def group(id: AbsolutePathId, version: Timestamp): Future[Option[Group]] = async {
    val root = await(groupRepository.rootVersion(version.toOffsetDateTime))
    root.flatMap(_.group(id))
  }

  override def runSpec(id: AbsolutePathId): Option[RunSpec] = app(id).orElse(pod(id))

  override def app(id: AbsolutePathId): Option[AppDefinition] = rootGroup().app(id)

  override def apps(ids: Set[AbsolutePathId]) = ids.map(appId => appId -> app(appId))(collection.breakOut)

  override def pod(id: AbsolutePathId): Option[PodDefinition] = rootGroup().pod(id)

  override def updateRootEither[T](
    id: AbsolutePathId,
    change: RootGroup => Future[Either[T, RootGroup]],
    version: Timestamp, force: Boolean, toKill: Map[AbsolutePathId, Seq[Instance]]): Future[Either[T, DeploymentPlan]] = try {

    rootGroupUpdatesMetric.increment()

    // All updates to the root go through the work queue.
    val maybeDeploymentPlan: Future[Either[T, DeploymentPlan]] = serializeUpdates {
      logger.info(s"Upgrade root group version:$version with force:$force")

      val from = rootGroup()
      async {
        await(checkMaxRunningDeployments())

        val changedGroup = await(change(from))
        changedGroup match {
          case Left(left) =>
            Left(left)
          case Right(changed) =>
            // Assign service ports
            val unversioned = AssignDynamicServiceLogic.assignDynamicServicePorts(
              Range.inclusive(config.localPortMin(), config.localPortMax()),
              from,
              changed)

            val withVersionedApps = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, unversioned)
            val withVersionedAppsPods = GroupVersioningUtil.updateVersionInfoForChangedPods(version, from, withVersionedApps)
            Validation.validateOrThrow(withVersionedAppsPods)(RootGroup.validRootGroup(config))
            val plan = DeploymentPlan(from, withVersionedAppsPods, version, toKill)
            Validation.validateOrThrow(plan)(DeploymentPlan.deploymentPlanValidator())
            logger.info(s"Computed new deployment plan for ${plan.targetIdsString}:\n$plan")
            await(groupRepository.storeRootVersion(plan.target, plan.createdOrUpdatedApps, plan.createdOrUpdatedPods))
            await(deploymentService.get().deploy(plan, force))
            await(groupRepository.storeRoot(plan.target, plan.createdOrUpdatedApps, plan.deletedApps, plan.createdOrUpdatedPods, plan.deletedPods))
            logger.info(s"Updated groups/apps/pods according to plan ${plan.id} for ${plan.targetIdsString}")
            // finally update the root under the write lock.
            root := Option(plan.target)
            Right(plan)
        }
      }
    }

    maybeDeploymentPlan.onComplete { _ =>
      rootGroupUpdatesMetric.decrement()
    }

    maybeDeploymentPlan.onComplete {
      case Success(Right(plan)) =>
        logger.info(s"Deployment ${plan.id}:${plan.version} for ${plan.targetIdsString} acknowledged. Waiting to get processed")
        eventStream.publish(GroupChangeSuccess(id, version.toString))
      case Success(Left(_)) =>
        ()
      case Failure(RejectionException(_: Rejection.AccessDeniedRejection)) =>
        // If the request was not authorized, we should not publish an event
        logger.warn(s"Deployment failed for change: $version; Access denied.")
      case Failure(NonFatal(ex)) =>
        logger.warn(s"Deployment failed for change: $version", ex)
        eventStream.publish(GroupChangeFailed(id, version.toString, ex.getMessage))
    }
    maybeDeploymentPlan
  } catch {
    case NonFatal(ex) => Future.failed(ex)
    case t: Throwable =>
      logger.error(s"A fatal error occurred during a root group update for change $version", t)
      throw t
  }

  def checkMaxRunningDeployments(): Future[Done] = async {
    val max = config.maxRunningDeployments()
    val num = await(deploymentService.get().listRunningDeployments()).size
    if (num >= max) {
      deploymentsDismissedMetric.increment()
      throw new TooManyRunningDeploymentsException(max)
    }
    Done
  }

  override def patchRoot(change: RootGroup => RootGroup): Future[Done] = {
    val from = rootGroup()
    async {
      val changedGroup = change(from)
      await(groupRepository.storeRoot(changedGroup, Seq.empty, Seq.empty, Seq.empty, Seq.empty))
      root := Option(changedGroup)
      Done
    }
  }

  override def invalidateAndRefreshGroupCache(): Future[Done] = async {
    root := None

    // propagation of reset group caches on repository is needed,
    // because manager and repository are holding own caches
    await(groupRepository.invalidateGroupCache())

    // force fetching of the root group from the group repository
    rootGroup()
    Done
  }

  override def invalidateGroupCache(): Future[Done] = async {
    root := None
    // propagation of reset group caches on repository is needed,
    // because manager and repository are holding own caches
    await(groupRepository.invalidateGroupCache())

    Done
  }

  private[this] val metricsRegistered: AtomicBoolean = new AtomicBoolean(false)
  private[this] def registerMetrics(): Unit = {
    if (metricsRegistered.compareAndSet(false, true)) {
      def apps(): Long = {
        rootGroupOption().foldLeft(0L) { (_, group) =>
          group.transitiveApps.size.toLong
        }
      }
      // We've already released metrics using these names, so we can't use the Metrics.* methods
      metrics.closureGauge("apps.active", () => apps())

      def pods(): Long = {
        rootGroupOption().foldLeft(0L) { (_, group) =>
          group.transitivePods.size.toLong
        }
      }
      metrics.closureGauge("pods.active", () => pods())

      def groups(): Long = {
        rootGroupOption().foldLeft(0L) { (_, group) =>
          group.transitiveGroupsById.size.toLong
        }
      }
      metrics.closureGauge("groups.active", () => groups())
    }
  }
}
