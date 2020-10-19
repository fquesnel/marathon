package mesosphere.marathon
package core.health

import org.rogach.scallop.ScallopConf
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

trait HealthCheckShieldConf extends ScallopConf {
  private[this] lazy val _healthCheckShieldFeatureEnabled = toggle(
    "health_check_shield_feature",
    default = Some(true),
    descrYes = "(Default) Activates /shield HTTP API which prevents Marathon from killing the specific unhealthy instance.",
    descrNo = "All requests to /shield API will return 503. The shields will not be loaded from the storage.",
    prefix = "disable_",
    noshort = true
  )

  private[this] lazy val _healthCheckShieldPurgePeriod = opt[Long](
    "health_check_shield_purge_period",
    default = Some(5.minutes.toMillis),
    descr = "How often expired heatlh check shields will be purged from the storage, in milliseconds",
    noshort = true
  )

  lazy val healthCheckShieldFeatureEnabled: Boolean = _healthCheckShieldFeatureEnabled()
  lazy val healthCheckShieldPurgePeriod: FiniteDuration = _healthCheckShieldPurgePeriod().millis
}