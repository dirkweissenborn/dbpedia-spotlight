package org.dbpedia.spotlight.model

import org.dbpedia.spotlight.db.memory.{MemoryResourceStore, MemoryContextStore, MemoryCandidateMapStore}
import scala.collection.mutable
import org.dbpedia.spotlight.io.{EntityTopicModelDocumentsSource, WikiOccurrenceSource}
import scala.util.Random
import scala.collection.JavaConversions._
import com.esotericsoftware.kryo.Kryo
import java.io.{FileInputStream, FileOutputStream, File}
import scala.Array
import com.esotericsoftware.kryo.io.{Input, Output}
import org.dbpedia.extraction.util.Language
import org.dbpedia.spotlight.db.{WikipediaToDBpediaClosure, SpotlightModel}
import org.dbpedia.spotlight.db.tokenize.LanguageIndependentTokenizer
import org.dbpedia.spotlight.db.stem.SnowballStemmer
import java.util.Locale
import org.dbpedia.spotlight.log.SpotlightLog

/**
 * @author dirk
 *         Date: 4/23/14
 *         Time: 12:16 PM
 */
class SimpleEntityTopicModel(val numTopics: Int, val numEntities: Int, val vocabularySize: Int, val numMentions: Int,
                             val alpha: Double, val beta: Double, val gamma: Double, val delta: Double, create:Boolean = false,
                             candMap: MemoryCandidateMapStore = null, contextStore:MemoryContextStore = null) {

  private val resStore:MemoryResourceStore = if(candMap != null) candMap.resourceStore.asInstanceOf[MemoryResourceStore] else null

  var entityTopicMatrix:Array[Array[Int]] = null
  var sparseWordEntityMatrix:Array[java.util.HashMap[Int,Int]] = null
  var sparseMentionEntityMatrix:Array[java.util.HashMap[Int,Int]] = null

  var topicCounts:Array[Int] = null
  var entityCounts:Array[Int] = null
  var assignmentCounts:Array[Int] = null

  if(create) {
    entityTopicMatrix = Array.ofDim[Int](numTopics, numEntities)
    sparseWordEntityMatrix = Array.fill(vocabularySize)(new java.util.HashMap[Int, Int]())
    topicCounts = new Array[Int](numTopics)
    entityCounts = new Array[Int](numEntities)
    assignmentCounts = new Array[Int](numEntities)
    sparseMentionEntityMatrix = Array.fill(numMentions)(new java.util.HashMap[Int, Int]())
  }

  private def getCountMap(elements: Array[Int]): mutable.HashMap[Int, Int] =
    elements.foldLeft(mutable.HashMap[Int, Int]())((acc, element) => {
      if (element >= 0)
        acc += (element -> (acc.getOrElse(element, 0) + 1))
      acc
    })
  

  private def updateCountMap(oldKey:Int,newKey:Int,map:mutable.Map[Int,Int]) {
    if(oldKey != newKey) {
      if(oldKey >= 0) {
        val oldValue = map(oldKey)
        if(oldValue == 1) map.remove(oldKey)
        else map(oldKey) = oldValue-1
      }
      map += newKey -> (map.getOrElse(newKey,0) + 1)
    }
  }

  def trainWithDocument(doc:EntityTopicDocument, firstTime:Boolean, iterations:Int = 1) {
    if(doc.mentions.length > 0) {
      val oldDoc = doc.clone()
      gibbsSampleDocument(doc, iterations = iterations, training = true, init = firstTime)

      //Perform synchronized update
      this.synchronized {
        (0 until oldDoc.mentions.length).foreach(idx => {
          val mention = doc.mentions(idx)
          val oldEntity = oldDoc.mentionEntities(idx)
          val oldTopic = oldDoc.entityTopics(idx)
          val newEntity = doc.mentionEntities(idx)
          val newTopic = doc.entityTopics(idx)

          //Update total topic counts
          if(firstTime || newTopic != oldTopic) {
            if(!firstTime && oldTopic >= 0)
              topicCounts(oldTopic) -= 1
            topicCounts(newTopic) += 1
          }

          if(firstTime || oldEntity != newEntity) {
            //update total entity counts
            if(!firstTime && oldEntity >= 0)
              entityCounts(oldEntity) -= 1
            entityCounts(newEntity) += 1

            //update entity-mention counts
            val mentionCounts = sparseMentionEntityMatrix(mention)
            updateCountMap({ if(firstTime) -1 else oldEntity },newEntity,mentionCounts)
          }

          //update entity-topic counts
          if(firstTime || oldEntity!= newEntity || oldTopic != newTopic) {
            if (!firstTime && oldTopic >= 0 && oldEntity >= 0)
              entityTopicMatrix(oldTopic)(oldEntity) -= 1
            entityTopicMatrix(newTopic)(newEntity) += 1
          }
        })

        (0 until doc.tokens.length).foreach(idx => {
          val token = doc.tokens(idx)
          val oldEntity = oldDoc.tokenEntities(idx)
          val newEntity = doc.tokenEntities(idx)

          if(firstTime || oldEntity != newEntity) {
            //update total assignment counts
            if(!firstTime && oldEntity >= 0)
              assignmentCounts(oldEntity) -= 1
            assignmentCounts(newEntity) += 1

            //update context counts
            val tokenCounts = sparseWordEntityMatrix(token)
            updateCountMap({ if(firstTime) -1 else oldEntity },newEntity,tokenCounts)
          }
        })
      }
    }
  }

  def gibbsSampleDocument(doc: EntityTopicDocument, iterations:Int = 300, training: Boolean = false, init: Boolean = false, returnStatistics:Boolean = false) = {
    val docTopicCounts = getCountMap(doc.entityTopics)
    val docEntityCounts = getCountMap(doc.mentionEntities)
    val docAssignmentCounts = getCountMap(doc.tokenEntities)

    val stats = if(returnStatistics) doc.mentionEntities.map(_ => mutable.Map[Int,Int]()) else null

    require(!training || iterations == 1, "At the moment training is only possible with iterations=1, because after one iteration information about old assignments is lost!")

    (0 until iterations).foreach(i => {

      { //Sample Topics & Entities
        (0 until doc.entityTopics.length).foreach(idx => {
          val oldEntity = doc.mentionEntities(idx)
          val oldTopic = doc.entityTopics(idx)
          val mention = doc.mentions(idx)

          //topic
          val newTopic = if (init && oldTopic >= 0)
            oldTopic
          else
            sampleFromProportionals(topic => {
              val localAdd = if (topic == oldTopic) -1 else 0
              val globalAdd = if (training) localAdd else 0
              if (oldEntity >= 0)
                (docTopicCounts.getOrElse(topic, 0) + localAdd + alpha) *
                  (entityTopicMatrix(topic)(oldEntity) + globalAdd + beta) / (topicCounts(topic) + globalAdd + numTopics * beta)
              else
                (docTopicCounts.getOrElse(topic, 0) + localAdd + alpha) *
                  (localAdd + beta) / (topicCounts(topic) + localAdd + numTopics * beta)
            }, 0 until numTopics)

          doc.entityTopics(idx) = newTopic

          //local update
          if(i < iterations - 1)
            updateCountMap(oldTopic, newTopic, docTopicCounts)

          //entity
          val mentionCounts = sparseMentionEntityMatrix(mention)
          val newEntity = if (init && oldEntity >= 0)
            oldEntity
          else {
            val entityTopicCounts = entityTopicMatrix(newTopic)
            val cands:Iterable[Int] =
              if(candMap == null) mentionCounts.keys
              else candMap.candidates(mention)

            val candCounts:Map[Int,Int] = if(init && candMap != null) cands.zip(candMap.candidateCounts(mention).map(candMap.qc)).toMap else null

            require(!cands.isEmpty,s"There are no candidates for mention id $mention")

            sampleFromProportionals(entity => {
              val localAdd = if (entity == oldEntity) -1 else 0
              val add = if (training) localAdd else 0
              val cte = entityTopicCounts(entity)
              //if initial phase use counts from statistical model
              val (cem,ce) =
                if(init && candMap != null) (candCounts(entity) ,resStore.qc(resStore.supportForID(entity)))
                else (mentionCounts.getOrElse(entity,0)+add,entityCounts(entity)+add)

              val docAssCount = docAssignmentCounts.getOrElse(entity, 0)
              val docCount = docEntityCounts.getOrElse(entity,0) + localAdd
              require(docCount >= 0, s"Document count of entity $entity cannot be lower than 0!\n"+docEntityCounts.toString)

              //if topic has changed, nothing has to be subtracted from global cte
              (cte + {if(newTopic == oldTopic) add else 0} + beta) *
                (cem + gamma) / (ce + numMentions * gamma) *
                { if(docCount > 0 && docAssCount > 0 ) math.pow((docCount + 1) / docCount, docAssCount)
                else 1
                }
            }, cands)
          }

          doc.mentionEntities(idx) = newEntity

          //local update
          updateCountMap(oldEntity,newEntity,docEntityCounts)

          if(returnStatistics)
            updateCountMap(oldEntity,newEntity,stats(idx))
        })
      }

      { //Sample new assignments
        val candidateEntities = docEntityCounts.keySet
        if (!candidateEntities.isEmpty)
          (0 until doc.tokenEntities.length).foreach(idx => {
            val oldEntity = doc.tokenEntities(idx)
            val token = doc.tokens(idx)
            val entityTokenCounts = sparseWordEntityMatrix(token)

            val newEntity = if(init && oldEntity >= 0)
              oldEntity
            else
              sampleFromProportionals(entity => {
                //if initial phase and context model is given, use counts from statistical model
                val (cew,ce) =
                  if(init && contextStore != null) {
                    val tokens = contextStore.tokens(entity)
                    if(tokens != null) {
                      val ct = (0 until contextStore.tokens(entity).length).
                        find(i => contextStore.tokens(entity)(i) == token).map(i => contextStore.qc(contextStore.counts(entity)(i)))
                      (ct.getOrElse(0), contextStore.totalTokenCounts(entity))
                    } else (0, contextStore.totalTokenCounts(entity))
                  } else  {
                    val add = if (training && entity == oldEntity) -1 else 0
                    (entityTokenCounts.getOrElse(entity, 0)+add, assignmentCounts(entity)+add)
                  }

                docEntityCounts(entity) *
                  (cew + delta) / (ce + vocabularySize * delta)
              }, candidateEntities)

            doc.tokenEntities(idx) = newEntity

            //local update
            if(i < iterations - 1)
              updateCountMap(oldEntity,newEntity,docAssignmentCounts)
          })
      }
    })

    stats
  }

  private[model] def sampleFromProportionals(calcProportion: Int => Double, candidates: Traversable[Int]) = {
    var sum = 0.0
    val cands = candidates.map(cand => {
      var res = calcProportion(cand)
      require(res >= 0.0,s"Calculating negative proportion is impossible! Candidate: $cand")
      sum += res
      (cand, res)
    }).toList

    if(cands.tail.isEmpty)
      cands.head._1
    else {
      var random = Random.nextDouble() * sum
      var selected = (Int.MinValue, 0.0)
      val it = cands.toIterator
      while (random >= 0.0 && it.hasNext) {
        selected = it.next()
        random -= selected._2
      }
      selected._1
    }
  }

}

object SimpleEntityTopicModel {
  private val kryo = new Kryo()

  kryo.register(classOf[Int])
  kryo.register(classOf[Double])
  kryo.register(classOf[Array[Int]])
  kryo.register(classOf[java.util.HashMap[Int, Int]])
  kryo.register(classOf[Array[Array[Int]]])
  kryo.register(classOf[Array[java.util.HashMap[Int, Int]]])

  def fromFile(file: File) = {
    SpotlightLog.info(getClass,s"Loading Entity-Topic-Model from ${file.getAbsolutePath}...")
    val start = System.currentTimeMillis()

    val in = new Input(new FileInputStream(file))
    val numTopics = kryo.readObject(in, classOf[Int])
    val numEntities = kryo.readObject(in, classOf[Int])
    val vocabularySize = kryo.readObject(in, classOf[Int])
    val numMentions = kryo.readObject(in, classOf[Int])

    val alpha = kryo.readObject(in, classOf[Double])
    val beta = kryo.readObject(in, classOf[Double])
    val gamma = kryo.readObject(in, classOf[Double])
    val delta = kryo.readObject(in, classOf[Double])

    val model = new SimpleEntityTopicModel(numTopics, numEntities, vocabularySize, numMentions, alpha, beta, gamma, delta)
    model.entityTopicMatrix = kryo.readObject(in, classOf[Array[Array[Int]]])
    model.sparseWordEntityMatrix = kryo.readObject(in, classOf[Array[java.util.HashMap[Int, Int]]])
    model.sparseMentionEntityMatrix= kryo.readObject(in, classOf[Array[java.util.HashMap[Int, Int]]])

    model.topicCounts = kryo.readObject(in, classOf[Array[Int]])
    model.entityCounts = kryo.readObject(in, classOf[Array[Int]])
    model.assignmentCounts = kryo.readObject(in, classOf[Array[Int]])

    in.close()
    SpotlightLog.info(getClass,s"loaded in ${System.currentTimeMillis()-start}ms!")
    model
  }

  def toFile(file: File, model: SimpleEntityTopicModel) {
    val out = new Output(new FileOutputStream(file))
    kryo.writeObject(out, model.numTopics)
    kryo.writeObject(out, model.numEntities)
    kryo.writeObject(out, model.vocabularySize)
    kryo.writeObject(out, model.numMentions)

    kryo.writeObject(out, model.alpha)
    kryo.writeObject(out, model.beta)
    kryo.writeObject(out, model.gamma)
    kryo.writeObject(out, model.delta)

    kryo.writeObject(out, model.entityTopicMatrix)
    kryo.writeObject(out, model.sparseWordEntityMatrix)
    kryo.writeObject(out, model.sparseMentionEntityMatrix)

    kryo.writeObject(out, model.topicCounts)
    kryo.writeObject(out, model.entityCounts)
    kryo.writeObject(out, model.assignmentCounts)
    out.close()
  }

  def main(args:Array[String]) {
    val locale = new Locale("en", "US")
    val namespace = if (locale.getLanguage.equals("en")) {
      "http://dbpedia.org/resource/"
    } else {
      "http://%s.dbpedia.org/resource/"
    }
    val modelDir = new File(args(0))
    val iterations = args(2).toInt
    val wikipediaToDBpediaClosure = new WikipediaToDBpediaClosure(
      namespace,
      new FileInputStream(new File(modelDir, "redirects.nt")),
      new FileInputStream(new File(modelDir, "disambiguations.nt"))
    )

    val (tokenStore, sfStore, resStore, candMap, _) = SpotlightModel.storesFromFolder(modelDir)
    val stopwords: Set[String] = SpotlightModel.loadStopwords(modelDir)

    val tokenizer =
      new LanguageIndependentTokenizer(stopwords, new SnowballStemmer("EnglishStemmer"), locale, tokenStore)

    args.drop(3).foreach(modelFile => {
      val model = fromFile(new File(modelFile))

      val occSource = WikiOccurrenceSource.fromXMLDumpFile(new File(args(1)), Language.English)
      var pr = 0
      var count = 0

      EntityTopicModelDocumentsSource.fromOccurrenceSource(occSource,null,tokenizer,resStore,sfStore,
        candMap.asInstanceOf[MemoryCandidateMapStore].candidates,wikipediaToDBpediaClosure).foreach(doc => {
        try {
          val goldStandard = doc.mentionEntities.clone()
          model.gibbsSampleDocument(doc,iterations=iterations)

          pr += goldStandard.zip(doc.mentionEntities).count(p => p._1 == p._2)
          count += goldStandard.length
        }
        catch {
          case t:Throwable => println(t.printStackTrace())
        }
      })

      if(count > 0 )
        SpotlightLog.info(getClass, "Precision: "+pr.toDouble/count)
      else
        SpotlightLog.info(getClass, "Nothing to evaluate!")
    })

  }
}