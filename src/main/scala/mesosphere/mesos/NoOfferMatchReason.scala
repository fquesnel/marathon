package mesosphere.mesos

import mesosphere.mesos.protos.Resource

sealed trait NoOfferMatchReason

object NoOfferMatchReason {
  case object InsufficientMemory extends NoOfferMatchReason
  case object InsufficientCpus extends NoOfferMatchReason
  case object InsufficientDisk extends NoOfferMatchReason
  case object InsufficientGpus extends NoOfferMatchReason
  case object InsufficientNetworkBandwidth extends NoOfferMatchReason
  case object InsufficientPorts extends NoOfferMatchReason
  case object UnfulfilledRole extends NoOfferMatchReason
  case object UnfulfilledConstraint extends NoOfferMatchReason
  case object AgentMaintenance extends NoOfferMatchReason
  case object NoCorrespondingReservationFound extends NoOfferMatchReason
  case object DeclinedScarceResources extends NoOfferMatchReason

  /**
    * This sequence is used to funnel reasons for not matching an offer.
    * If an offer is not matched, only one reason is taken into account.
    * This reason is chosen by the lowest index of this sequence.
    */
  val reasonFunnel = Seq(
    UnfulfilledRole,
    UnfulfilledConstraint,
    NoCorrespondingReservationFound,
    AgentMaintenance,
    InsufficientCpus,
    InsufficientMemory,
    InsufficientDisk,
    InsufficientGpus,
    InsufficientNetworkBandwidth,
    InsufficientPorts,
    DeclinedScarceResources
  )

  def fromResourceType(name: String): NoOfferMatchReason = name match {
    case Resource.CPUS => InsufficientCpus
    case Resource.DISK => InsufficientDisk
    case Resource.GPUS => InsufficientGpus
    case Resource.MEM => InsufficientMemory
    case Resource.PORTS => InsufficientPorts
    case Resource.NETWORK_BANDWIDTH => InsufficientNetworkBandwidth
    case _ => throw new IllegalArgumentException(s"Not able to match $name to NoOfferMatchReason")
  }
}
