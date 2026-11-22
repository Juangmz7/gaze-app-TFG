package com.app.postcommandservice;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Stand-in for the {@code ObjectProvider<Self>} that services use to call their own
 * transactional methods through the Spring proxy. A plain unit test has no proxy, so it
 * provides the instance itself once constructed, which behaves like a proxy forwarding to
 * the real bean:
 * <pre>{@code
 * var self = new SelfProvider<MyService>();
 * var service = self.set(new MyService(..., self));
 * }</pre>
 */
public final class SelfProvider<T> implements ObjectProvider<T> {

    private T self;

    public T set(T self) {
        this.self = self;
        return self;
    }

    @Override
    public T getObject() {
        return self;
    }
}
