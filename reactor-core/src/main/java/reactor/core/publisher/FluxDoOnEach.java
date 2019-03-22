/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.function.Consumer;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Fuseable.ConditionalSubscriber;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

/**
 * Peek into the lifecycle events and signals of a sequence
 *
 * @param <T> the value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxDoOnEach<T> extends FluxOperator<T, T> {

	final Consumer<? super Signal<T>> onSignal;

	FluxDoOnEach(Flux<? extends T> source, Consumer<? super Signal<T>> onSignal) {
		super(source);
		this.onSignal = Objects.requireNonNull(onSignal, "onSignal");
	}

	@SuppressWarnings("unchecked")
	static <T> DoOnEachSubscriber<T> createSubscriber(CoreSubscriber<? super T> actual,
			Consumer<? super Signal<T>> onSignal, boolean fuseable) {
		if (fuseable) {
			if(actual instanceof ConditionalSubscriber) {
				return new DoOnEachFuseableConditionalSubscriber<>((ConditionalSubscriber<? super T>) actual, onSignal);
			}
			return new DoOnEachFuseableSubscriber<>(actual, onSignal);
		}

		if (actual instanceof ConditionalSubscriber) {
			return new DoOnEachConditionalSubscriber<>((ConditionalSubscriber<? super T>) actual, onSignal);
		}
		return new DoOnEachSubscriber<>(actual, onSignal);
	}

	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		source.subscribe(createSubscriber(actual, onSignal, false));
	}

	static class DoOnEachSubscriber<T> implements InnerOperator<T, T>, Signal<T> {

		final CoreSubscriber<? super T>   actual;
		final Context                     cachedContext;
		final Consumer<? super Signal<T>> onSignal;

		T t;

		Subscription s;
		@Nullable
		Fuseable.QueueSubscription<T> qs;

		boolean done;

		DoOnEachSubscriber(CoreSubscriber<? super T> actual,
				Consumer<? super Signal<T>> onSignal) {
			this.actual = actual;
			this.cachedContext = actual.currentContext();
			this.onSignal = onSignal;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				this.qs = Operators.as(s);
				actual.onSubscribe(this);
			}
		}

		@Override
		public Context currentContext() {
			return cachedContext;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) {
				return s;
			}
			if (key == Attr.TERMINATED) {
				return done;
			}

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t, cachedContext);
				return;
			}
			try {
				this.t = t;
				//noinspection ConstantConditions
				onSignal.accept(this);
			}
			catch (Throwable e) {
				onError(Operators.onOperatorError(s, e, t, cachedContext));
				return;
			}

			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t, cachedContext);
				return;
			}
			done = true;
			try {
				//noinspection ConstantConditions
				onSignal.accept(Signal.error(t, cachedContext));
			}
			catch (Throwable e) {
				//this performs a throwIfFatal or suppresses t in e
				t = Operators.onOperatorError(null, e, t, cachedContext);
			}

			try {
				actual.onError(t);
			}
			catch (UnsupportedOperationException use) {
				if (!Exceptions.isErrorCallbackNotImplemented(use) && use.getCause() != t) {
					throw use;
				}
				//ignore if missing callback
			}
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			try {
				onSignal.accept(Signal.complete(cachedContext));
			}
			catch (Throwable e) {
				done = false;
				onError(Operators.onOperatorError(s, e, cachedContext));
				return;
			}

			actual.onComplete();
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return actual;
		}

		@Nullable
		@Override
		public Throwable getThrowable() {
			return null;
		}

		@Nullable
		@Override
		public Subscription getSubscription() {
			return null;
		}

		@Nullable
		@Override
		public T get() {
			return t;
		}

		@Override
		public Context getContext() {
			return cachedContext;
		}

		@Override
		public SignalType getType() {
			return SignalType.ON_NEXT;
		}

		@Override
		public String toString() {
			return "doOnEach_onNext(" + t + ")";
		}
	}

	static class DoOnEachFuseableSubscriber<T> extends DoOnEachSubscriber<T>
			implements Fuseable, Fuseable.QueueSubscription<T> {

		boolean syncFused;

		DoOnEachFuseableSubscriber(CoreSubscriber<? super T> actual,
				Consumer<? super Signal<T>> onSignal) {
			super(actual, onSignal);
		}

		@Override
		public int requestFusion(int mode) {
			QueueSubscription<T> qs = this.qs;
			if (qs != null && (mode & Fuseable.THREAD_BARRIER) == 0) {
				int m = qs.requestFusion(mode);
				if (m != Fuseable.NONE) {
					syncFused = m == Fuseable.SYNC;
				}
				return m;
			}
			return Fuseable.NONE;
		}

		@Override
		public void clear() {
			qs.clear(); //throws NPE, but should only be called after onSubscribe on a Fuseable
		}

		@Override
		public boolean isEmpty() {
			return qs == null || qs.isEmpty();
		}

		@Override
		@Nullable
		public T poll() {
			if (qs == null) {
				return null;
			}
			T v = qs.poll();
			if (v == null && syncFused) {
				done = true;
				try {
					onSignal.accept(Signal.complete(cachedContext));
				}
				catch (Throwable e) {
					throw e;
				}
			} else if (v != null) {
				this.t = v;
				onSignal.accept(this); //throws in case of error
			}
			return v;
		}

		@Override
		public int size() {
			return qs == null ? 0 : qs.size();
		}
	}

	static final class DoOnEachConditionalSubscriber<T> extends DoOnEachSubscriber<T>
			implements ConditionalSubscriber<T> {

		DoOnEachConditionalSubscriber(ConditionalSubscriber<? super T> actual,
				Consumer<? super Signal<T>> onSignal) {
			super(actual, onSignal);
		}

		@Override
		@SuppressWarnings("unchecked")
		public boolean tryOnNext(T t) {
			boolean result = ((ConditionalSubscriber<? super T>)actual).tryOnNext(t);
			if (result) {
				this.t = t;
				onSignal.accept(this);
			}
			return result;
		}
	}

	static final class DoOnEachFuseableConditionalSubscriber<T> extends DoOnEachFuseableSubscriber<T>
			implements ConditionalSubscriber<T> {

		DoOnEachFuseableConditionalSubscriber(ConditionalSubscriber<? super T> actual,
				Consumer<? super Signal<T>> onSignal) {
			super(actual, onSignal);
		}

		@Override
		@SuppressWarnings("unchecked")
		public boolean tryOnNext(T t) {
			boolean result = ((ConditionalSubscriber<? super T>) actual).tryOnNext(t);
			if (result) {
				this.t = t;
				onSignal.accept(this);
			}
			return result;
		}
	}
}