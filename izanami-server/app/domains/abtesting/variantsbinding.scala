package domains.abtesting

import akka.{Done, NotUsed}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import cats.Monad
import cats.effect.Effect
import domains.{ImportResult, Key}
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result.{AppErrors, ErrorMessage, Result}
import store._

import scala.concurrent.{ExecutionContext, Future}
import store.SourceUtils._
/* ************************************************************************* */
/*                      Variant binding                                      */
/* ************************************************************************* */

case class VariantBindingKey(experimentId: ExperimentKey, clientId: String) {
  def key: Key = Key.Empty / experimentId / clientId
}

object VariantBindingKey {

  implicit val format: Format[VariantBindingKey] = Format(
    Key.format.map { k =>
      VariantBindingKey(k)
    },
    Writes[VariantBindingKey](vk => Key.format.writes(vk.key))
  )

  def apply(key: Key): VariantBindingKey = {
    val last :: rest = key.segments.toList.reverse
    VariantBindingKey(Key(rest.reverse), last)
  }
}

case class VariantBinding(variantBindingKey: VariantBindingKey, variantId: String)

object VariantBinding {
  implicit val format = Json.format[VariantBinding]

  def importData[F[_]: Effect](
      variantBindingStore: VariantBindingStore[F]
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import cats.effect.implicits._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[VariantBinding]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          variantBindingStore.create(obj.variantBindingKey, obj).map { ImportResult.fromResult }.toIO.unsafeToFuture()
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  def variantFor[F[_]: Effect](experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      experimentStore: ExperimentStore[F],
      VariantBindingStore: VariantBindingStore[F]
  ): F[Result[Variant]] = {
    import cats.implicits._
    VariantBindingStore
      .getById(VariantBindingKey(experimentKey, clientId))
      .flatMap {
        case None =>
          createVariantForClient(experimentKey, clientId)
        case Some(v) =>
          experimentStore.getById(experimentKey).map {
            case Some(e) =>
              e.variants
                .find(_.id == v.variantId)
                .map(Result.ok)
                .getOrElse(Result.error("error.variant.missing"))
            case None =>
              Result.error("error.experiment.missing")
          }
      }
  }

  def createVariantForClient[F[_]: Effect](experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      experimentStore: ExperimentStore[F],
      VariantBindingStore: VariantBindingStore[F]
  ): F[Result[Variant]] = {

    import cats.implicits._
    import libs.functional.syntax._

    object OPs extends EitherTSyntax[F]
    import OPs._

    // Each step is an EitherT[Future, StoreError, ?]. EitherT is a monad transformer where map, flatMap, withFilter is implemented for Future[Either[L, R]]
    // something  |> someOp, aim to transform "something" into EitherT[Future, StoreError, ?]
    (
      for {
        //Get experiment, if empty : StoreError
        experiment <- experimentStore.getById(experimentKey) |> liftFOption(
                       AppErrors(Seq(ErrorMessage("error.experiment.missing")))
                     )
        // Sum of the distinct client connected
        sum = experiment.variants.map { _.currentPopulation.getOrElse(0) }.sum
        // We find the variant with the less value
        lastChosenSoFar = experiment.variants.maxBy {
          case v if sum == 0 => 0
          case v =>
            val pourcentReached = v.currentPopulation.getOrElse(0).toFloat / sum
            val diff            = v.traffic - pourcentReached
            //println(s"${v.id} => traffic: ${v.traffic} diff: $diff, reached: $pourcentReached, current ${v.currentPopulation.getOrElse(0).toFloat}, sum: $sum")
            v.traffic - v.currentPopulation.getOrElse(0).toFloat / sum
        }
        // Update of the variant
        newLeastChosenVariantSoFar = lastChosenSoFar.incrementPopulation
        // Update of the experiment
        newExperiment = experiment.addOrReplaceVariant(newLeastChosenVariantSoFar)
        // Update OPS on store for experiment
        experimentUpdated <- experimentStore.update(experimentKey, experimentKey, newExperiment) |> liftFEither[
                              AppErrors,
                              Experiment
                            ]
        // Variant binding creation
        variantBindingKey       = VariantBindingKey(experimentKey, clientId)
        binding: VariantBinding = VariantBinding(variantBindingKey, newLeastChosenVariantSoFar.id)
        variantBindingCreated <- VariantBindingStore.create(variantBindingKey, binding) |> liftFEither[AppErrors,
                                                                                                       VariantBinding]
      } yield newLeastChosenVariantSoFar
    ).value
  }

}

trait VariantBindingStore[F[_]] extends DataStore[F, VariantBindingKey, VariantBinding]

class VariantBindingStoreImpl[F[_]: Monad](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends VariantBindingStore[F]
    with EitherTSyntax[F] {

  import cats.data._
  import cats.implicits._
  import store.Result._
  import libs.functional.syntax._
  import domains.events.Events._

  import VariantBinding._

  override def create(id: VariantBindingKey, data: VariantBinding): F[Result[VariantBinding]] = {
    // format: off
    val r: EitherT[F, AppErrors, VariantBinding] = for {
      created     <- jsonStore.create(id.key, VariantBinding.format.writes(data))   |> liftFEither
      vBinding    <- created.validate[VariantBinding]                               |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(VariantBindingCreated(id, vBinding))        |> liftF[AppErrors, Done]
    } yield vBinding
    // format: on
    r.value
  }

  override def update(oldId: VariantBindingKey,
                      id: VariantBindingKey,
                      data: VariantBinding): F[Result[VariantBinding]] = {
    // format: off
    val r: EitherT[F, AppErrors, VariantBinding] = for {
      oldValue    <- getById(oldId)                                                   |> liftFOption(AppErrors.error("error.data.missing", oldId.key.key))
      updated     <- jsonStore.update(oldId.key, id.key, VariantBinding.format.writes(data))  |> liftFEither
      vBinding    <- updated.validate[VariantBinding]                                   |> liftJsResult{ handleJsError }
    } yield vBinding
    // format: on
    r.value
  }

  override def delete(id: VariantBindingKey): F[Result[VariantBinding]] = {
    // format: off
    val r: EitherT[F, AppErrors, VariantBinding] = for {
      deleted   <- jsonStore.delete(id.key)             |> liftFEither
      vBinding  <- deleted.validate[VariantBinding]     |> liftJsResult{ handleJsError }
    } yield vBinding
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: VariantBindingKey): F[Option[VariantBinding]] =
    jsonStore.getById(id.key).map(_.flatMap(_.validate[VariantBinding].asOpt))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[VariantBinding]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(VariantBindingKey, VariantBinding), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[VariantBinding].map {
      case (k, v) => (VariantBindingKey(k), v)
    }

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}
