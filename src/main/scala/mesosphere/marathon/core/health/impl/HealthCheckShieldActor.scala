package mesosphere.marathon
package core.health.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.health.impl.HealthCheckShieldActor._

import scala.concurrent.duration._
import scala.concurrent.Await
import akka.actor.{Actor, Props, Timers}

import scala.concurrent.Future
import akka.{Done}

// This actor serves two purposes:
// 1. Detect the leadership change, so that the cache in HealthCheckShieldManager is updated when we become a leader
// 2. Schedule a periodical purge of the expired health check shields
private[health] class HealthCheckShieldActor(
    manager: HealthCheckShieldManager
) extends Actor with Timers with StrictLogging {
  var lastPurge: Future[Done] = Future.successful(Done)

  override def preStart() = {
    val cacheInitFuture = manager.fillCacheFromStorage()
    // There's no way in Marathon codebase to wait for asynchronous preload
    // By blocking, we give the future at least some time to populate the cache
    Await.result(cacheInitFuture, atMost = 5.seconds)
    // Trigger the first purge immediately, then purge periodically
    self ! Purge
    schedulePurge()
  }

  def schedulePurge() = {
    val purgeInterval = 5.minutes
    timers.startPeriodicTimer(TimerKey, Purge, purgeInterval)
    logger.info("[health-check-shield] Scheduled periodical purge")
  }

  def receive: Receive = {
    case Purge =>
      // ensure we have only one purge at a time
      if (lastPurge.isCompleted) {
        lastPurge = manager.purgeExpiredShields()
      }
  }
}

object HealthCheckShieldActor {
  case class TimerKey()
  case class Purge()

  def props(manager: HealthCheckShieldManager): Props =
    Props(new HealthCheckShieldActor(manager))
}
