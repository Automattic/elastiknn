package com.klibisz.elastiknn.query

import com.klibisz.elastiknn.ElastiknnException.ElastiknnRuntimeException
import com.klibisz.elastiknn.api.NearestNeighborsQuery._
import com.klibisz.elastiknn.api._
import com.klibisz.elastiknn.mapper.VectorMapper
import com.klibisz.elastiknn.models.{Cache, SparseIndexedSimilarityFunction}
import com.klibisz.elastiknn.models.{ExactSimilarityFunction => ESF}
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.Query
import org.elasticsearch.common.lucene.search.function.ScoreFunction
import org.elasticsearch.index.mapper.MappedFieldType
import org.elasticsearch.index.query.SearchExecutionContext

import scala.language.implicitConversions
import scala.util._

/**
  * Useful way to represent a query. The name is meh.
  */
trait ElastiknnQuery[V <: Vec] {
  def toLuceneQuery(indexReader: IndexReader): Query
  def toScoreFunction(indexReader: IndexReader): ScoreFunction
}

object ElastiknnQuery {

  private def incompatible(q: NearestNeighborsQuery, m: Mapping): Exception = {
    val msg = s"Query [${ElasticsearchCodec.encode(q).noSpaces}] is not compatible with mapping [${ElasticsearchCodec.encode(m).noSpaces}]"
    new IllegalArgumentException(msg)
  }

  def getMapping(context: SearchExecutionContext, field: String): Mapping = {
    import VectorMapper._
    val mft: MappedFieldType = context.getFieldType(field)
    mft match {
      case ft: FieldType => ft.mapping
      case null =>
        throw new ElastiknnRuntimeException(s"Could not find mapped field type for field [${field}]")
      case _ =>
        throw new ElastiknnRuntimeException(
          s"Expected field [${mft.name}] to have type [${denseFloatVector.CONTENT_TYPE}] or [${sparseBoolVector.CONTENT_TYPE}] but had [${mft.typeName}]"
        )
    }
  }

  def apply(query: NearestNeighborsQuery, queryShardContext: SearchExecutionContext): Try[ElastiknnQuery[_]] =
    apply(query, getMapping(queryShardContext, query.field))

  private implicit def toSuccess[A <: Vec](q: ElastiknnQuery[A]): Try[ElastiknnQuery[A]] = Success(q)

  def apply(query: NearestNeighborsQuery, mapping: Mapping): Try[ElastiknnQuery[_]] =
    (query, mapping) match {

      case (
          Exact(f, Similarity.Jaccard, minScore: Double, v: Vec.SparseBool),
          _: Mapping.SparseBool | _: Mapping.SparseIndexed | _: Mapping.JaccardLsh | _: Mapping.HammingLsh
          ) =>
        new ExactQuery(f, v, ESF.Jaccard)

      case (
          Exact(f, Similarity.Hamming, minScore: Double, v: Vec.SparseBool),
          _: Mapping.SparseBool | _: Mapping.SparseIndexed | _: Mapping.JaccardLsh | _: Mapping.HammingLsh
          ) =>
        new ExactQuery(f, v, ESF.Hamming)

      case (
          Exact(f, Similarity.L1, minScore: Double, v: Vec.DenseFloat),
          _: Mapping.DenseFloat | _: Mapping.CosineLsh | _: Mapping.L2Lsh | _: Mapping.PermutationLsh
          ) =>
        new ExactQuery(f, v, ESF.L1)

      case (
          Exact(f, Similarity.L2, minScore: Double, v: Vec.DenseFloat),
          _: Mapping.DenseFloat | _: Mapping.CosineLsh | _: Mapping.L2Lsh | _: Mapping.PermutationLsh
          ) =>
        new ExactQuery(f, v, ESF.L2)

      case (
          Exact(f, Similarity.Cosine, minScore: Double, v: Vec.DenseFloat),
          _: Mapping.DenseFloat | _: Mapping.CosineLsh | _: Mapping.L2Lsh | _: Mapping.PermutationLsh
          ) =>
        new ExactQuery(f, v, ESF.Cosine)

      case (SparseIndexed(f, Similarity.Jaccard, minScore: Double, sbv: Vec.SparseBool), _: Mapping.SparseIndexed) =>
        new SparseIndexedQuery(f,minScore, sbv, SparseIndexedSimilarityFunction.Jaccard)

      case (SparseIndexed(f, Similarity.Hamming, minScore: Double, sbv: Vec.SparseBool), _: Mapping.SparseIndexed) =>
        new SparseIndexedQuery(f,minScore, sbv, SparseIndexedSimilarityFunction.Hamming)

      case (JaccardLsh(f, candidates, minScore: Double, v: Vec.SparseBool), m: Mapping.JaccardLsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.trueIndices, v.totalIndices), ESF.Jaccard)

      case (HammingLsh(f, candidates, minScore: Double, v: Vec.SparseBool), m: Mapping.HammingLsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.trueIndices, v.totalIndices), ESF.Hamming)

      case (CosineLsh(f, candidates, minScore: Double, v: Vec.DenseFloat), m: Mapping.CosineLsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.values), ESF.Cosine)

      case (L2Lsh(f, candidates, probes, minScore: Double, v: Vec.DenseFloat), m: Mapping.L2Lsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.values, probes), ESF.L2)

      case (PermutationLsh(f, Similarity.Cosine, candidates, minScore: Double, v: Vec.DenseFloat), m: Mapping.PermutationLsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.values), ESF.Cosine)

      case (PermutationLsh(f, Similarity.L2, candidates, minScore: Double, v: Vec.DenseFloat), m: Mapping.PermutationLsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.values), ESF.L2)

      case (PermutationLsh(f, Similarity.L1, candidates, minScore: Double, v: Vec.DenseFloat), m: Mapping.PermutationLsh) =>
        new HashingQuery(f, minScore, v, candidates, Cache(m).hash(v.values), ESF.L1)

      case _ => Failure(incompatible(query, mapping))
    }
}
