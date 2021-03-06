package mesosphere.marathon
package core.health

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp

sealed trait HealthResult {
  def instanceId: Instance.Id
  def taskId: Task.Id
  def version: Timestamp
  def time: Timestamp
  def publishEvent: Boolean
}

case class Healthy(
    instanceId: Instance.Id,
    taskId: Task.Id,
    version: Timestamp,
    time: Timestamp = Timestamp.now(),
    publishEvent: Boolean = true) extends HealthResult

case class Unhealthy(
    instanceId: Instance.Id,
    taskId: Task.Id,
    version: Timestamp,
    cause: String,
    time: Timestamp = Timestamp.now(),
    publishEvent: Boolean = true) extends HealthResult

/**
  * Representing an ignored HTTP response code (see [[MarathonHttpHealthCheck.ignoreHttp1xx]]. Will not update the
  * health check state and not be published.
  */
case class Ignored(
    instanceId: Instance.Id,
    taskId: Task.Id,
    version: Timestamp,
    time: Timestamp = Timestamp.now(),
    publishEvent: Boolean = false) extends HealthResult
