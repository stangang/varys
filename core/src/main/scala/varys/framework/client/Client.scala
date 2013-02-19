package varys.framework.client

import varys.framework._
import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.pattern.AskTimeoutException
import varys.{VarysException, Logging}
import akka.remote.RemoteClientLifeCycleEvent
import akka.remote.RemoteClientShutdown
import varys.framework.RegisterCoflow
import varys.framework.master.Master
import akka.remote.RemoteClientDisconnected
import akka.actor.Terminated
import akka.dispatch.Await

/**
 * The main class used to talk to a Varys framework cluster. Takes a master URL, a coflow description,
 * and a listener for coflow events, and calls back the listener when various events occur.
 */
private[varys] class Client(
    actorSystem: ActorSystem,
    masterUrl: String,
    coflowDescription: CoflowDescription,
    listener: ClientListener)
  extends Logging {

  var actor: ActorRef = null
  var coflowId: String = null

  class ClientActor extends Actor with Logging {
    var master: ActorRef = null
    var masterAddress: Address = null
    var alreadyDisconnected = false  // To avoid calling listener.disconnected() multiple times

    override def preStart() {
      logInfo("Connecting to master " + masterUrl)
      try {
        master = context.actorFor(Master.toAkkaUrl(masterUrl))
        masterAddress = master.path.address
        master ! RegisterCoflow(coflowDescription)
        context.system.eventStream.subscribe(self, classOf[RemoteClientLifeCycleEvent])
        context.watch(master)  // Doesn't work with remote actors, but useful for testing
      } catch {
        case e: Exception =>
          logError("Failed to connect to master", e)
          markDisconnected()
          context.stop(self)
      }
    }

    override def receive = {
      case RegisteredCoflow(coflowId_) =>
        coflowId = coflowId_
        listener.connected(coflowId)

      case Terminated(actor_) if actor_ == master =>
        logError("Connection to master failed; stopping client")
        markDisconnected()
        context.stop(self)

      case RemoteClientDisconnected(transport, address) if address == masterAddress =>
        logError("Connection to master failed; stopping client")
        markDisconnected()
        context.stop(self)

      case RemoteClientShutdown(transport, address) if address == masterAddress =>
        logError("Connection to master failed; stopping client")
        markDisconnected()
        context.stop(self)

      case StopClient =>
        markDisconnected()
        sender ! true
        context.stop(self)
    }

    /**
     * Notify the listener that we disconnected, if we hadn't already done so before.
     */
    def markDisconnected() {
      if (!alreadyDisconnected) {
        listener.disconnected()
        alreadyDisconnected = true
      }
    }
  }

  def start() {
    // Just launch an actor; it will call back into the listener.
    actor = actorSystem.actorOf(Props(new ClientActor))
  }

  def stop() {
    if (actor != null) {
      try {
        val timeout = 5.seconds
        val future = actor.ask(StopClient)(timeout)
        Await.result(future, timeout)
      } catch {
        case e: AskTimeoutException =>  // Ignore it, maybe master went away
      }
      actor = null
    }
  }
}