
import com.graphrag.api.models.{ExecutionTrace, TraceStep}
import com.graphrag.api.services.ExplainService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class ExplainServiceTest extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "ExplainService" should "initialize without errors" in {
    val service = new ExplainService()
    service should not be null
    service.close()
  }

  it should "handle close lifecycle" in {
    val service = new ExplainService()
    noException should be thrownBy service.close()
  }


  "getTrace" should "return None for non-existent requestId" in {
    val service = new ExplainService()

    val retrieved = Await.result(service.getTrace("non-existent"), 1.second)
    retrieved should be(None)

    service.close()
  }

}