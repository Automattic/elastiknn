package org.elasticsearch.elastiknn.query

import org.elasticsearch.elastiknn.KNearestNeighborsQuery.{LshQueryOptions, QueryOptions}
import org.elasticsearch.elastiknn.ProcessorOptions.ModelOptions
import org.elasticsearch.elastiknn.Similarity.SIMILARITY_JACCARD
import org.elasticsearch.elastiknn._
import org.scalatest.{AsyncFunSuite, Inspectors, Matchers}

class LshQuerySuite
    extends AsyncFunSuite
    with QuerySuite
    with Matchers
    with SilentMatchers
    with Inspectors
    with Elastic4sMatchers
    with ElasticAsyncClient {

  private val simToOpts: Map[Similarity, Seq[ModelOptions]] = Map[Similarity, Seq[ModelOptions]](
    SIMILARITY_JACCARD -> Seq(
      ModelOptions.Jaccard(JaccardLshOptions(0, "vec_proc", 1, 10, 1)),
      ModelOptions.Jaccard(JaccardLshOptions(0, "vec_proc", 1, 10, 3)),
      ModelOptions.Jaccard(JaccardLshOptions(0, "vec_proc", 2, 20, 3))
    )
  ).withDefault((_: Similarity) => Seq.empty[ModelOptions])

  for {
    sim <- Similarity.values
    opt <- simToOpts(sim)
    dim <- testDataDims
  } {

    val support = new Support("vec_raw", sim, dim, opt)

    test(s"approximate search given vector: ($dim, $sim, $opt)") {
      support.testGiven(QueryOptions.Lsh(LshQueryOptions(support.pipelineId))) { queriesAndResponses =>
        forAtLeast((queriesAndResponses.length * 0.7).floor.toInt, queriesAndResponses.silent) {
          case (query, res) =>
            res.hits.hits should not be empty
            val correctCorpusIds = query.indices.map(support.corpusId).toSet
            val returnedCorpusIds = res.hits.hits.map(_.id).toSet
            correctCorpusIds.intersect(returnedCorpusIds) should not be empty
        }
      }
    }

    test(s"approximate search with indexed vector: ($dim, $sim, $opt)") {
      support.testIndexed(QueryOptions.Lsh(LshQueryOptions(support.pipelineId))) { queriesAndResponses =>
        forAll(queriesAndResponses.silent) {
          case (_, id, res) =>
            res.hits.hits should not be empty
            // Top hit should be the query vector itself.
            val self = res.hits.hits.find(_.id == id)
            self shouldBe defined
            self.map(_.score) shouldBe Some(res.hits.hits.map(_.score).max)
        }
      }
    }

  }

}
