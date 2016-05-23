package scorex.transaction

import org.h2.mvstore.MVStore
import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.crypto.encode.Base58
import scorex.transaction.state.{MinimalState, StateElement}
import scorex.utils.ScorexLogging

import scala.util.{Failure, Success, Try}

/**
  * Storage interface combining both history(blockchain/blocktree) and state
  */
trait BlockStorage[TX <: Transaction[_]] extends ScorexLogging {

  val db: MVStore

  val MaxRollback: Int

  val history: History[TX]

  def state: MinimalState[TX]

  //Append block to current state
  def appendBlock(block: Block[TX]): Try[Unit] = synchronized {
    history.appendBlock(block).map { blocks =>
      blocks foreach { b =>
        state.processBlock(b) match {
          case Failure(e) =>
            log.error("Failed to apply block to state", e)
            db.rollback()
          case Success(m) =>
            db.commit()
        }
      }
    }.recoverWith { case e =>
      log.error("Failed to append block:", e)
      Failure(e)
    }
  }

  //Should be used for linear blockchain only
  def removeAfter(signature: BlockId): Unit = synchronized {
    history match {
      case h: BlockChain[TX] => h.heightOf(signature) match {
        case Some(height) =>
          while (!h.lastBlock.uniqueId.sameElements(signature)) h.discardBlock()
          state.rollbackTo(height)
        case None =>
          log.warn(s"RemoveAfter non-existing block ${Base58.encode(signature)}")
      }
      case _ =>
        throw new RuntimeException("Not available for other option than linear blockchain")
    }
  }


}

object BlockStorage {

  sealed trait Direction

  case object Forward extends Direction

  case object Reversed extends Direction
}
