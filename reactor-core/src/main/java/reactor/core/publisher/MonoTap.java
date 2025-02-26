/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.observability.SignalListener;
import reactor.core.observability.SignalListenerFactory;
import reactor.core.publisher.FluxTap.TapSubscriber;
import reactor.util.annotation.Nullable;

/**
 * A generic per-Subscription side effect {@link Mono} that notifies a {@link SignalListener} of most events.
 *
 * @author Simon Baslé
 */
final class MonoTap<T, STATE> extends InternalMonoOperator<T, T> {

	final SignalListenerFactory<T, STATE> tapFactory;
	final STATE                           commonTapState;

	MonoTap(Mono<? extends T> source, SignalListenerFactory<T, STATE> tapFactory) {
		super(source);
		this.tapFactory = tapFactory;
		this.commonTapState = tapFactory.initializePublisherState(source);
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) throws Throwable {
		//if the SequenceObserver cannot be created, all we can do is error the subscriber.
		//after it is created, in case doFirst fails we can additionally try to invoke doFinally.
		//note that if the later handler also fails, then that exception is thrown.
		SignalListener<T> signalListener;
		try {
			//TODO replace currentContext() with contextView() when available
			signalListener = tapFactory.createListener(source, actual.currentContext().readOnly(), commonTapState);
		}
		catch (Throwable generatorError) {
			Operators.error(actual, generatorError);
			return null;
		}
		// Attempt to wrap the SignalListener with one that restores ThreadLocals from Context on each listener methods
		// (only if ContextPropagation.isContextPropagationAvailable() is true)
		signalListener = ContextPropagation.contextRestoreForTap(signalListener, actual::currentContext);

		try {
			signalListener.doFirst();
		}
		catch (Throwable listenerError) {
			signalListener.handleListenerError(listenerError);
			Operators.error(actual, listenerError);
			return null;
		}

		if (actual instanceof Fuseable.ConditionalSubscriber) {
			//noinspection unchecked
			return new FluxTap.TapConditionalSubscriber<>((Fuseable.ConditionalSubscriber<? super T>) actual, signalListener);
		}
		return new TapSubscriber<>(actual, signalListener);
	}

	@Nullable
	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.PREFETCH) return -1;
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

		return super.scanUnsafe(key);
	}
}
