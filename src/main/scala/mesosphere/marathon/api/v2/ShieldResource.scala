package mesosphere.marathon
package api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Context, MediaType}
import mesosphere.marathon.api._
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer}
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.raml.HealthConversion._

import scala.async.Async._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Path("v2/shield")
@Produces(Array(MediaType.APPLICATION_JSON))
class HealthCheckShieldResource @Inject() (
    val config: MarathonConf,
    healthCheckManager: HealthCheckManager,
    val authenticator: Authenticator,
    val authorizer: Authorizer)(implicit val executionContext: ExecutionContext) extends AuthResource {

  @GET
  def index(
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async{
      val shields = healthCheckManager.listShields()
      ok(Raml.toRaml(shields))
    }
  }

  @PUT
  @Path("{taskId}")
  def putHealthShield(
    @PathParam("taskId") taskId: String,
    @QueryParam("duration")@DefaultValue("30 minutes") duration: String,
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val parsedDuration = Duration(duration)
      if (parsedDuration.isFinite()) {
        val ttl = parsedDuration.asInstanceOf[FiniteDuration]
        healthCheckManager.enableShield(Task.Id.parse(taskId), ttl)
        ok (s"Enabled HC for ${taskId} with TTL ${ttl}!")
      } else {
        status(Status.BAD_REQUEST)
      }
    }
  }

  @DELETE
  @Path("{taskId}")
  def removeHealthShield(
    @PathParam("taskId") taskId: String,
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      healthCheckManager.disableShield(Task.Id.parse(taskId))
      ok(s"Disabled HC for ${taskId}!")
    }
  }
}
