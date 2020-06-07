/*****************************************************************************
 * ------------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License");           *
 * you may not use this file except in compliance with the License.          *
 * You may obtain a copy of the License at                                   *
 *                                                                           *
 * http://www.apache.org/licenses/LICENSE-2.0                                *
 *                                                                           *
 * Unless required by applicable law or agreed to in writing, software       *
 * distributed under the License is distributed on an "AS IS" BASIS,         *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package com.google.mu.util.stream;

import static com.google.mu.util.stream.MoreStreams.whileNotEmpty;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Implements generic graph and tree traversal algorithms ({@link #preOrderFrom pre-order},
 * {@link #postOrderFrom post-order} and {@link #breadthFirstFrom breadth-first}) as lazily
 * evaluated streams, allowing infinite-size graphs.
 *
 * <p>None of these streams are safe to run in parallel.
 *
 * @since 3.9
 */
public final class Walker<T> {
  private final Function<? super T, ? extends Stream<? extends T>> findSuccessors;
  private final Predicate<? super T> tracker;

  private Walker(
      Function<? super T, ? extends Stream<? extends T>> findSuccessors,
      Predicate<? super T> tracker) {
    this.findSuccessors = requireNonNull(findSuccessors);
    this.tracker = requireNonNull(tracker);
  }

  /**
   * Returns a {@code Traversal} object assuming tree structure (no cycles), using {@code
   * getChildren} to find children of any given tree node.
   *
   * <p>The returned object is idempotent, stateless and immutable as long as {@code getChildren} is
   * idempotent, stateless and immutable.
   *
   * <p>WARNING: the returned {@code Walker} can generate infinite cycles if {@code getChildren}
   * behaves like a graph with cycles (for example any undirected graph).
   *
   * @param getChildren Function to get the child nodes for a given node.
   *        No children if empty stream or null is returned,
   */
  public static <T> Walker<T> newTreeWalker(
      Function<? super T, ? extends Stream<? extends T>> getChildren) {
    return newWalker(getChildren, n -> true);
  }

  /**
   * Returns a {@code Traversal} object assuming graph structure (with cycles), using {@code
   * findSuccessors} to find successor nodes of any given graph node.
   *
   * <p>The returned object remembers which nodes have been traversed, thus if you call for example
   * {@link #preOrderFrom} again, already visited nodes will be skipped. This is useful if you need
   * to imperatively and dynamically decide which node to traverse. For example, the SHIELD and
   * Avengers may need to collaborately raid a building from multiple entry points:
   *
   * <pre>{@code
   * Walker<Room> walker = Walker.newGraphWalker(buildingMap);
   * Stream<Room> shield = walker.preOrderFrom(roof);
   * Stream<Room> avengers = walker.breadthFirstFrom(mainEntrance);
   * // Now the two teams collaborate while raiding, no room is traversed twice...
   * }</pre>
   *
   * In the normal case though, you'd likely always want to start clean, in which case,
   * just recreate the {@code Walker} object.
   *
   * <p>Because the {@code Traversal} object keeps memory of traversal history, the memory usage is
   * linear to the number of traversed nodes.
   *
   * @param findSuccessors Function to get the successor nodes for a given node.
   *        No successor if empty stream or null is returned,
   */
  public static <T> Walker<T> newGraphWalker(
      Function<? super T, ? extends Stream<? extends T>> findSuccessors) {
    return newWalker(findSuccessors, new HashSet<>()::add);
  }

  /**
   * Similar to {@link #newGraphWalker(Function)}, returns a {@code Walker} that can be used to
   * traverse a graph of nodes. {@code tracker} is used to track every node being traversed. When
   * {@code Walker} is about to traverse a node, {@code tracker.test(node)} will be called and the
   * node will be skipped if false is returned.
   *
   * <p>This is useful for custom node tracking. For example, the caller could use a {@link
   * java.util.TreeSet} or some {@code EquivalenceSet} to compare nodes using custom equality or
   * equivalence; or, use a {@link java.util.ConcurrentHashMap} if multiple threads need to walk the
   * same graph concurrently and collaboratively:
   *
   * <pre>{@code
   * Walker<Room> concurrentWalker =
   *     Walker.newWalker(buildingMap, ConcurrentHashMap.newKeySet()::add);
   *
   * // thread 1:
   * Stream<Room> shield = concurrentWalker.preOrderFrom(roof);
   * // iterate through rooms raided by the SHIELD agents.
   *
   * // thread 2:
   * Stream<Room> avengers = concurrentWalker.breadthFirstFrom(mainEntrance);
   * // iterate through rooms raided by Avengers.
   * }</pre>
   *
   * @param findSuccessors Function to get the successor nodes for a given node.
   *        No successor if empty stream or null is returned,
   * @param tracker Tracks each node being visited during traversal. Returns false if the node
   *        should be skipped for traversal (for example because it has already been traversed).
   */
  public static <T> Walker<T> newWalker(
      Function<? super T, ? extends Stream<? extends T>> findSuccessors,
      Predicate<? super T> tracker) {
    return new Walker<>(findSuccessors, tracker);
  }

  /**
   * Starts from {@code initials} and traverse depth first in pre-order by using {@code
   * findSuccessors} function iteratively.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  @SafeVarargs
  public final Stream<T> preOrderFrom(T... initials) {
    return preOrderFrom(nonNullList(initials));
  }

  /**
   * Starts from {@code initials} and traverse depth first in pre-order.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  public final Stream<T> preOrderFrom(Iterable<? extends T> initials) {
    return new Traversal().preOrder(initials);
  }

  /**
   * Starts from {@code initials} and traverse depth first in post-order.
   *
   * <p>The returned stream may be infinite if the graph has infinite breadth. The stream can still
   * be short-circuited to consume a limited number of nodes during traversal.
   *
   * <p>The stream may result in infinite loop when it traversing through a node with infinite
   * depth.
   */
  @SafeVarargs
  public final Stream<T> postOrderFrom(T... initials) {
    return postOrderFrom(nonNullList(initials));
  }

  /**
   * Starts from {@code initials} and traverse depth first in post-order.
   *
   * <p>The returned stream may be infinite if the graph has infinite breadth. The stream can still
   * be short-circuited to consume a limited number of nodes during traversal.
   *
   * <p>The stream may result in infinite loop when it traversing through a node with infinite
   * depth.
   */
  public final Stream<T> postOrderFrom(Iterable<? extends T> initials) {
    return new Traversal().postOrder(initials);
  }

  /**
   * Starts from {@code initials} and traverse in breadth-first order.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  @SafeVarargs
  public final Stream<T> breadthFirstFrom(T... initials) {
    return breadthFirstFrom(nonNullList(initials));
  }

  /**
   * Starts from {@code initials} and traverse in breadth-first order.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  public final Stream<T> breadthFirstFrom(Iterable<? extends T> initials) {
    return new Traversal().breadthFirst(initials);
  }

  private final class Traversal implements Consumer<T> {
    private final Deque<Spliterator<? extends T>> horizon = new ArrayDeque<>();
    private T visited;

    @Override
    public void accept(T value) {
      this.visited = requireNonNull(value);
    }

    Stream<T> breadthFirst(Iterable<? extends T> initials) {
      horizon.add(initials.spliterator());
      return topDown(Queue::add);
    }

    Stream<T> preOrder(Iterable<? extends T> initials) {
      horizon.push(initials.spliterator());
      return topDown(Deque::push);
    }

    Stream<T> postOrder(Iterable<? extends T> initials) {
      horizon.push(initials.spliterator());
      Deque<T> post = new ArrayDeque<>();
      return whileNotEmpty(horizon).map(h -> removeFromBottom(post)).filter(Objects::nonNull);
    }

    private Stream<T> topDown(InsertionOrder order) {
      return whileNotEmpty(horizon).map(h -> removeFromTop(order)).filter(Objects::nonNull);
    }

    private T removeFromTop(InsertionOrder traversalOrder) {
      do {
        if (visitNext()) {
          T next = visited;
          Stream<? extends T> successors = findSuccessors.apply(next);
          if (successors != null) {
            traversalOrder.insertInto(horizon, successors.spliterator());
          }
          return next;
        }
        horizon.removeFirst();
      } while (!horizon.isEmpty());
      return null; // no more element
    }

    private T removeFromBottom(Deque<T> postStack) {
      while (visitNext()) {
        T next = visited;
        Stream<? extends T> successors = findSuccessors.apply(next);
        if (successors == null) {
          return next;
        }
        horizon.push(successors.spliterator());
        postStack.push(next);
      }
      horizon.pop();
      return postStack.pollFirst();
    }

    private boolean visitNext() {
      Spliterator<? extends T> top = horizon.getFirst();
      while (top.tryAdvance(this)) {
        if (tracker.test(visited)) {
          return true;
        }
      }
      return false;
    }
  }

  @SafeVarargs
  private static <T> List<T> nonNullList(T... values) {
    return Arrays.stream(values).peek(Objects::requireNonNull).collect(toList());
  }

  private interface InsertionOrder {
    <T> void insertInto(Deque<T> deque, T value);
  }
}