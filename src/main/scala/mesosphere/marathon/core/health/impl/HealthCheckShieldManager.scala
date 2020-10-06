package mesosphere.marathon
package core.health.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.core.health.HealthCheckShield

import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap

class HealthCheckShieldManager extends Object with StrictLogging {

  private[this] val shields = TrieMap.empty[Task.Id, HealthCheckShield]

  def isShielded(taskId: Task.Id): Boolean = {
    shields.get(taskId)
      .exists(shield => {
        if (Timestamp.now() < shield.ttl) {
          true
        } else {
          shields.remove(taskId, shield)
          logger.info(s"[health-check-shield] Shield expired for ${taskId}")
          false
        }
      })
  }

  def enableShield(taskId: Task.Id, duration: FiniteDuration): Unit = {
    shields.update(taskId, HealthCheckShield(taskId, Timestamp.now() + duration))
    logger.info(s"[health-check-shield] Shield enabled for ${taskId}")
  }

  def disableShield(taskId: Task.Id): Unit = {
    shields.remove(taskId)
    logger.info(s"[health-check-shield] Shield disabled for ${taskId}")
  }

  def getShields(): Seq[HealthCheckShield] = {
    shields.values.to[Seq]
  }
}
