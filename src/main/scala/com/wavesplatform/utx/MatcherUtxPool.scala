package com.wavesplatform.utx

import akka.event.EventStream
import com.wavesplatform.matcher.model.Events.BalanceChanged
import com.wavesplatform.mining.TwoDimensionalMiningConstraint
import com.wavesplatform.state2.{ByteStr, Portfolio}
import scorex.account.Address
import scorex.transaction.{Authorized, Transaction, ValidationError}
import scorex.utils.ScorexLogging

import scala.collection.mutable

class MatcherUtxPool(underlying: UtxPool, events: EventStream) extends UtxPool with ScorexLogging {
  override def putIfNew(tx: Transaction): Either[ValidationError, Boolean] = {
    underlying
      .putIfNew(tx)
      .map { r =>
        if (r) {
          log.debug(s"Checking $tx, ${tx.isInstanceOf[Authorized]}")
          tx match {
            case tx: Authorized =>
              val msg = BalanceChanged(Map(tx.sender.address -> portfolio(tx.sender)))
              log.debug(s"Sending $msg")
              events.publish(msg)
            case _ =>
          }
        }
        r
      }
  }

  override def close(): Unit = underlying.close()
  override def removeAll(txs: Traversable[Transaction]): Unit = underlying.removeAll(txs)
  override def portfolio(addr: Address): Portfolio = underlying.portfolio(addr)
  override def all: Seq[Transaction] = underlying.all
  override def size: Int = underlying.size
  override def transactionById(transactionId: ByteStr): Option[Transaction] = underlying.transactionById(transactionId)

  override def packUnconfirmed(rest: TwoDimensionalMiningConstraint,
                               sortInBlock: Boolean)
    : (Seq[Transaction], TwoDimensionalMiningConstraint) = underlying.packUnconfirmed(rest, sortInBlock)

  override def batched(f: UtxBatchOps => Unit): Unit = {
    val ops = new BatchOpsImpl(underlying.createBatchOps)
    f(ops)
    val msg = ops.message
    log.debug(s"Sending $msg")
    events.publish(msg)
  }

  override def createBatchOps: UtxBatchOps = new BatchOpsImpl(underlying.createBatchOps)

  private class BatchOpsImpl(underlying: UtxBatchOps) extends UtxBatchOps {
    private val accountInfos: mutable.Map[String, Portfolio] = mutable.Map.empty

    def message: BalanceChanged = BalanceChanged(accountInfos.toMap)

    override def putIfNew(tx: Transaction): Either[ValidationError, Boolean] = underlying
      .putIfNew(tx)
      .map { r =>
        if (r) {
          log.debug(s"Checking $tx, ${tx.isInstanceOf[Authorized]}")
          tx match {
            case tx: Authorized => accountInfos += tx.sender.address -> portfolio(tx.sender)
            case _ =>
          }
        }
        r
      }
  }
}
