/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Begin copy to new root during GC **/
  case object BeginCopy

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case GC => {
      context become garbageCollecting(createRoot)
      self ! BeginCopy
    }
    case m: Operation => root forward m
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case BeginCopy => root ! CopyTo(newRoot)
    case CopyFinished => {
      context stop root
      root = newRoot
      context become normal
      pendingQueue foreach (self forward _)
      pendingQueue = Queue.empty[Operation]
    }
    case m: Operation => pendingQueue = pendingQueue enqueue m
  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished
  case object CheckCopyStatus

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

object MyCompare {
  sealed trait CMP
  case object LT extends CMP
  case object GT extends CMP
  case object EQ extends CMP

  def compare(x: Int, y: Int): CMP = if (x < y) LT else if (x > y) GT else EQ
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._
  import MyCompare._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case m@Insert(requester, id, newElem) => compare(elem, newElem) match {
      case EQ => {
        removed = false
        requester ! OperationFinished(id)
      }
      case LT => insertElem(Left, m)
      case GT => insertElem(Right, m)
    }

    case m: Contains => handleMessage(
      m,
      m => m.requester ! ContainsResult(m.id, result = !removed),
      m => m.requester ! ContainsResult(m.id, result = false)
    )

//    case m@Contains(requester, id, newElem) => compare(elem, newElem) match {
//      case EQ => requester ! ContainsResult(id, result = !removed)
//      case LT => checkElem(Left, m)
//      case GT => checkElem(Right, m)
//    }

    case m: Remove => handleMessage(
      m,
      { m => removed = true; m.requester ! OperationFinished(m.id) },
      m => m.requester ! OperationFinished(m.id)
    )

//    case m@Remove(requester, id, remElem) => compare(elem, remElem) match {
//      case EQ => {
//        removed = true
//        requester ! OperationFinished(id)
//      }
//      case LT => removeElem(Left, m)
//      case GT => removeElem(Right, m)
//    }

    case m@CopyTo(newRoot) => {
      val myNodes = subtrees.values.toSet
      context become copying(myNodes, insertConfirmed = false, scala.util.Random.nextInt())
      myNodes foreach (_ ! m)
      self forward m
    }
  }

  def insertElem(pos: Position, m: Insert) = subtrees get pos match {
    case Some(tree) => tree forward m
    case None => {
      val newNode = (pos, context actorOf BinaryTreeNode.props(m.elem, initiallyRemoved = false))
      subtrees = subtrees + newNode
      m.requester ! OperationFinished(m.id)
    }
  }

//  def checkElem(pos: Position, m: Contains) = subtrees get pos match {
//    case Some(tree) => tree forward m
//    case None => m.requester ! ContainsResult(m.id, result = false)
//  }
//
//  def removeElem(pos: Position, m: Remove) = subtrees get pos match {
//    case Some(tree) => tree forward m
//    case None => m.requester ! OperationFinished(m.id)
//  }

  type OpFunc = Function1[Operation, Unit]

  def delegateMessage(pos: Position, m: Operation, actOn: OpFunc) = subtrees get pos match {
    case Some(tree) => tree forward m
    case None => actOn(m)
  }

  def handleMessage(m: Operation, onEQ: OpFunc, onOther: OpFunc) = compare(elem, m.elem) match {
    case EQ => onEQ(m)
    case LT => delegateMessage(Left, m, onOther)
    case GT => delegateMessage(Right, m, onOther)
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean, myId: Int): Receive = {
    case CopyTo(newRoot) =>
      if (!removed) newRoot ! Insert(self, myId, elem)
      else self ! OperationFinished(myId)
    case OperationFinished(id) if id == myId => {
      context become copying(expected, insertConfirmed = true, myId)
      self ! CheckCopyStatus
    }
    case CopyFinished => {
      context become copying(expected - sender(), insertConfirmed, myId)
      self ! CheckCopyStatus
    }
    case CheckCopyStatus => if (expected.isEmpty && insertConfirmed) {
      context.parent ! CopyFinished
      context become normal
    }
  }
}
