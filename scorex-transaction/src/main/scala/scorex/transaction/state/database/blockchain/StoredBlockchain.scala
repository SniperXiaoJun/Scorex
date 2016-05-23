package scorex.transaction.state.database.blockchain

import org.h2.mvstore.{MVMap, MVStore}
import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.consensus.ConsensusModule
import scorex.transaction.History.BlockchainScore
import scorex.transaction.account.Account
import scorex.transaction.{BlockChain, Transaction, TransactionModule}
import scorex.utils.ScorexLogging

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

/**
 * If no datafolder provided, blockchain lives in RAM (useful for tests)
 */
class StoredBlockchain[TX <: Transaction[_]](db: MVStore)
                                            (implicit consensusModule: ConsensusModule[_, _, TX],
                                             transactionModule: TransactionModule[_, TX])
  extends BlockChain[TX] with ScorexLogging {

  case class BlockchainPersistence(database: MVStore) {
    val blocks: MVMap[Int, Array[Byte]] = database.openMap("blocks")
    val signatures: MVMap[Int, BlockId] = database.openMap("signatures")
    val scoreMap: MVMap[Int, BigInt] = database.openMap("score")

    //if there are some uncommited changes from last run, discard'em
    if (signatures.size() > 0) database.rollback()

    def writeBlock(height: Int, block: Block[TX]): Try[Unit] = Try {
      blocks.put(height, block.bytes)
      scoreMap.put(height, score() + block.consensusModule.blockScore(block)(block.transactionModule))
      signatures.put(height, block.uniqueId)
    }

    def readBlock(height: Int): Option[Block[TX]] =
      Try(Option(blocks.get(height))).toOption.flatten.flatMap(b => Block.parseBytes(b).toOption)

    def deleteBlock(height: Int): Unit = {
      blocks.remove(height)
      signatures.remove(height)
    }

    def contains(id: BlockId): Boolean = signatures.exists(_._2.sameElements(id))

    def height(): Int = signatures.size()

    def heightOf(id: BlockId): Option[Int] = signatures.find(_._2.sameElements(id)).map(_._1)

    def score(): BlockchainScore = if (height() > 0) scoreMap.get(height()) else 0

  }

  private val blockStorage: BlockchainPersistence = new BlockchainPersistence(db)

  override private[transaction] def appendBlock(block: Block[TX]): Try[Seq[Block[TX]]] = synchronized {
    Try {
      val parent = block.referenceField
      if ((height() == 0) || (lastBlock.uniqueId sameElements parent.value)) {
        val h = height() + 1
        blockStorage.writeBlock(h, block) match {
          case Success(_) => Seq(block)
          case Failure(t) => throw new Error("Error while storing blockchain a change: " + t)
        }
      } else {
        throw new Error(s"Appending block ${block.json} which parent is not last block in blockchain")
      }
    }
  }


  override private[transaction] def discardBlock(): BlockChain[TX] = synchronized {
    require(height() > 1, "Chain is empty or contains genesis block only, can't make rollback")
    val h = height()
    blockStorage.deleteBlock(h)
    this
  }

  override def blockAt(height: Int): Option[Block[TX]] = synchronized {
    blockStorage.readBlock(height)
  }

  override def lastBlockIds(howMany: Int): Seq[BlockId] =
    (Math.max(1, height() - howMany + 1) to height()).flatMap(i => Option(blockStorage.signatures.get(i))).reverse

  override def contains(signature: Array[Byte]): Boolean = blockStorage.contains(signature)

  override def height(): Int = blockStorage.height()

  override def score(): BlockchainScore = blockStorage.score()

  override def heightOf(blockSignature: Array[Byte]): Option[Int] = blockStorage.heightOf(blockSignature)

  override def blockById(blockId: BlockId): Option[Block[TX]] = heightOf(blockId).flatMap(blockAt)

  override def children(block: Block[TX]): Seq[Block[TX]] = heightOf(block).flatMap(h => blockAt(h + 1)).toSeq

  override def generatedBy(account: Account): Seq[Block[TX]] =
    (1 to height()).toStream.flatMap { h =>
      blockAt(h).flatMap { block =>
        if (block.consensusModule.producers(block).contains(account)) Some(block) else None
      }
    }

  override def toString: String = ((1 to height()) map { case h =>
    val bl = blockAt(h).get
    s"$h -- ${bl.uniqueId.mkString} -- ${bl.referenceField.value.mkString}"
  }).mkString("\n")
}
