/*
 *  Copyright 2006 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.core3;

import java.rmi.RemoteException;

/**
 * Object passed to a Stub instance in order for it to actually communicate
 * with a remote object.
 *
 * @author Brian S O'Neill
 * @see StubFactory
 */
public interface StubSupport {
    /**
     * Returns an InvocationChannel for writing method identifier and
     * arguments, and for reading response. Caller flushes output after
     * arguments are written, and then channel is read from.
     *
     * @throws java.rmi.NoSuchObjectException if support has been disposed
     */
    <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureException) throws T;

    /**
     * Called after channel usage is finished and can be reused for sending
     * new requests. This method should not throw any exception.
     */
    void finished(InvocationChannel channel);

    /**
     * Called if invocation failed due to a problem with the channel, and it
     * should be closed. This method should not throw any exception, however it
     * must return an appropriate Throwable which will get thrown to the client.
     */
    <T extends Throwable> T failed(Class<T> remoteFailureException,
                                   InvocationChannel channel, Throwable cause);

    /**
     * Returns a hashCode implementation for the Stub.
     */
    int stubHashCode();

    /**
     * Returns a partial equals implementation for the Stub.
     */
    boolean stubEquals(StubSupport support);

    /**
     * Returns a partial toString implementation for the Stub.
     */
    String stubToString();
}