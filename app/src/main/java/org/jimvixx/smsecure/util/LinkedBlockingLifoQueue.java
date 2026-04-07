package org.jimvixx.smsecure.util;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * A BlockingQueue adapter that pushes new elements to the *front* of an internal deque,
 * giving LIFO behavior when used as a ThreadPoolExecutor work queue.
 */
public final class LinkedBlockingLifoQueue<E> implements BlockingQueue<E> {

  private final LinkedBlockingDeque<E> deque;

  public LinkedBlockingLifoQueue() {
    this.deque = new LinkedBlockingDeque<>();
  }

  public LinkedBlockingLifoQueue(int capacity) {
    this.deque = new LinkedBlockingDeque<>(capacity);
  }

  // --- LIFO semantics for producer side ---
  @Override
  public boolean offer(E e) {
    return deque.offerFirst(e);
  }

  @Override
  public void put(E e) throws InterruptedException {
    deque.putFirst(e);
  }

  @Override
  public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
    return deque.offerFirst(e, timeout, unit);
  }

  // --- Consumer side: take from front (LIFO) ---
  @Override
  public E take() throws InterruptedException {
    return deque.takeFirst();
  }

  @Override
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
    return deque.pollFirst(timeout, unit);
  }

  @Override
  public E poll() {
    return deque.pollFirst();
  }

  @Override
  public E peek() {
    return deque.peekFirst();
  }

  // --- Capacity / draining ---
  @Override
  public int remainingCapacity() {
    return deque.remainingCapacity();
  }

  @Override
  public int drainTo(Collection<? super E> c) {
    return deque.drainTo(c);
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    return deque.drainTo(c, maxElements);
  }

  // --- Queue / Collection delegation ---
  @Override
  public boolean add(E e) {
    deque.addFirst(e);
    return true;
  }

  @Override
  public E remove() {
    return deque.removeFirst();
  }

  @Override
  public E element() {
    return deque.getFirst();
  }

  @Override
  public int size() {
    return deque.size();
  }

  @Override
  public boolean isEmpty() {
    return deque.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return deque.contains(o);
  }

  @NonNull
  @Override
  public Iterator<E> iterator() {
    return deque.iterator();
  }

  @NonNull
  @Override
  public Object[] toArray() {
    return deque.toArray();
  }

  @NonNull
  @Override
  public <T> T[] toArray(@NonNull T[] a) {
    return deque.toArray(a);
  }

  @Override
  public boolean remove(Object o) {
    return deque.remove(o);
  }

  @Override
  public boolean containsAll(@NonNull Collection<?> c) {
    return deque.containsAll(c);
  }

  @Override
  public boolean addAll(@NonNull Collection<? extends E> c) {
    return deque.addAll(c);
  }

  @Override
  public boolean removeAll(@NonNull Collection<?> c) {
    return deque.removeAll(c);
  }

  @Override
  public boolean retainAll(@NonNull Collection<?> c) {
    return deque.retainAll(c);
  }

  @Override
  public void clear() {
    deque.clear();
  }
}