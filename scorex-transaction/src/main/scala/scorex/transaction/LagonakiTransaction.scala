package scorex.transaction

import com.google.common.primitives.Ints
import play.api.libs.json.Json
import scorex.crypto.encode.Base58
import scorex.serialization.{BytesParseable, BytesSerializable}
import scorex.transaction.LagonakiTransaction.TransactionType
import scorex.transaction.account.Account
import scorex.transaction.proof.{Proof, Signature25519}

import scala.concurrent.duration._
import scala.util.{Failure, Try}


abstract class LagonakiTransaction(val transactionType: TransactionType.Value,
                                   override val recipient: Account,
                                   val amount: Long,
                                   override val fee: Long,
                                   override val timestamp: Long,
                                   val signature: Array[Byte]) extends AccountTransaction with BytesSerializable {

  import LagonakiTransaction._

  override lazy val proof: Proof = Signature25519(signature)

  lazy val deadline = timestamp + 24.hours.toMillis

  lazy val feePerByte = fee / dataLength.toDouble
  lazy val hasMinimumFee = fee >= MinimumFee
  lazy val hasMinimumFeePerByte = {
    val minFeePerByte = 1.0 / MaxBytesPerToken
    feePerByte >= minFeePerByte
  }

  val TypeId = transactionType.id

  //PARSE/CONVERT
  val dataLength: Int

  //VALIDATE
  def validate: ValidationResult.Value

  def involvedAmount(account: Account): Long

  def balanceChanges(): Seq[(Account, Long)]

  override def equals(other: Any): Boolean = other match {
    case tx: LagonakiTransaction => signature.sameElements(tx.signature)
    case _ => false
  }

  override def hashCode(): Int = Ints.fromByteArray(signature)

  protected def jsonBase() = {
    Json.obj("type" -> transactionType.id,
      "fee" -> fee,
      "timestamp" -> timestamp,
      "signature" -> Base58.encode(this.signature)
    )
  }
}

object LagonakiTransaction extends BytesParseable[LagonakiTransaction] {

  val MaxBytesPerToken = 512

  //MINIMUM FEE
  val MinimumFee = 1
  val RecipientLength = Account.AddressLength
  val TypeLength = 1
  val TimestampLength = 8
  val AmountLength = 8

  object ValidationResult extends Enumeration {
    type ValidationResult = Value

    val ValidateOke = Value(1)
    val InvalidAddress = Value(2)
    val NegativeAmount = Value(3)
    val NegativeFee = Value(4)
    val NoBalance = Value(5)
  }

  //TYPES
  object TransactionType extends Enumeration {
    val GenesisTransaction = Value(1)
    val PaymentTransaction = Value(2)
  }

  def parseBytes(data: Array[Byte]): Try[LagonakiTransaction] =
    data.head match {
      case txType: Byte if txType == TransactionType.GenesisTransaction.id =>
        GenesisTransaction.parseBytes(data.tail)

      case txType: Byte if txType == TransactionType.PaymentTransaction.id =>
        PaymentTransaction.parseBytes(data.tail)

      case txType => Failure(new Exception(s"Invalid transaction type: $txType"))
    }

}
