/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datastax.spark.connector.writer

import java.util.concurrent.{CompletableFuture, CompletionStage, Semaphore}
import java.util.function.BiConsumer
import com.datastax.spark.connector.util.Logging

import scala.jdk.CollectionConverters._
import scala.collection.concurrent.TrieMap
import scala.util.Try
import AsyncExecutor.Handler
import com.datastax.oss.driver.api.core.{AllNodesFailedException, NoNodeAvailableException}
import com.datastax.oss.driver.api.core.connection.BusyConnectionException
import com.datastax.oss.driver.api.core.servererrors.OverloadedException

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

/** Asynchronously executes tasks but blocks if the limit of unfinished tasks is reached. */
class AsyncExecutor[T, R](asyncAction: T => CompletionStage[R], maxConcurrentTasks: Int,
                          successHandler: Option[Handler[T]] = None, failureHandler: Option[Handler[T]]) extends Logging {

  private val semaphore = new Semaphore(maxConcurrentTasks)
  private val pendingFutures = new TrieMap[Future[R], Boolean]

  @volatile private var latestException: Option[Throwable] = None

  /** Returns an exception if any of the futures had an exception.
    * Returning None means that no exceptions have been thrown.
    */
  def getLatestException(): Option[Throwable] = latestException

  /** Executes task asynchronously or blocks if more than `maxConcurrentTasks` limit is reached */
  def executeAsync(task: T): Future[R] = {
    val submissionTimestamp = System.nanoTime()
    semaphore.acquire()

    val promise = Promise[R]()
    pendingFutures.put(promise.future, true)

    val executionTimestamp = System.nanoTime()

    def tryFuture(): Future[R] = {
      val value = Try(asyncAction(task)) recover {
        case e =>
          val future = new CompletableFuture[R]()
          future.completeExceptionally(e)
          future
      }

      value.get.whenComplete(new BiConsumer[R, Throwable] {
        private def release() {
          semaphore.release()
          pendingFutures.remove(promise.future)
        }

        private def onSuccess(result: R) {
          release()
          promise.success(result)
          successHandler.foreach(_ (task, submissionTimestamp, executionTimestamp))
        }

        private def onFailure(throwable: Throwable) {
          throwable match {
            case e: AllNodesFailedException if e.getAllErrors.asScala.values.exists(_.isInstanceOf[BusyConnectionException]) =>
              logTrace("BusyConnectionException ... Retrying")
              tryFuture()
            case e: NoNodeAvailableException =>
              logTrace("No Nodes Available ... Retrying")
              tryFuture()
            case e: OverloadedException =>
              logTrace("Backpressure rejection ... Retrying")
              tryFuture()

            case otherException =>
              logError("Failed to execute: " + task, otherException)
              latestException = Some(throwable)
              release()
              promise.failure(throwable)
              failureHandler.foreach(_ (task, submissionTimestamp, executionTimestamp))
          }
        }

        override def accept(r: R, t: Throwable): Unit = {
          Option(t).foreach(onFailure)
          Option(r).foreach(onSuccess)
        }
      })

      promise.future
    }

    tryFuture()
  }

  def execute(task: T): R = {
    Await.result(executeAsync(task), Duration(20, SECONDS))
  }


    /** Waits until the tasks being currently executed get completed.
    * It will not wait for tasks scheduled for execution during this method call,
    * nor tasks for which the [[executeAsync]] method did not complete. */
  def waitForCurrentlyExecutingTasks() {
    for ((future, _) <- pendingFutures.snapshot())
      Try(Await.result(future, Duration.Inf))
  }
}

object AsyncExecutor {
  type Handler[T] = (T, Long, Long) => Unit
}
