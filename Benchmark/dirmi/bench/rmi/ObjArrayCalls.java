/*
 * Copyright 2000-2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 *
 */

package dirmi.bench.rmi;

import dirmi.bench.Benchmark;
import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Benchmark for testing speed of calls with object array parameters and return
 * values.
 */
public class ObjArrayCalls implements Benchmark {

    public static class Node implements Serializable {
        boolean z;
        byte b;
        char c;
        short s;
        int i;
        float f;
        long j;
        double d;
        String str = "bodega";
        Object parent, left, right;

        Node(Object parent, int depth) {
            this.parent = parent;
            if (depth > 0) {
                left = new Node(this, depth - 1);
                right = new Node(this, depth - 1);
            }
        }
    }

    public static interface Server extends Remote {
        public Node[] call(Node[] a) throws RemoteException;
    }

    static class ServerImpl implements Server {
        public ServerImpl() throws RemoteException {
        }

        public Node[] call(Node[] a) throws RemoteException {
            return a;
        }
    }

    static class ServerFactory implements BenchServer.RemoteObjectFactory {
        public Remote create() throws RemoteException {
            return new ServerImpl();
        }
    }

    /**
     * Issue calls using arrays of objects as parameters/return values.
     * Arguments: <array size> <# calls>
     */
    public long run(String[] args) throws Exception {
        int size = Integer.parseInt(args[0]);
        int reps = Integer.parseInt(args[1]);
        BenchServer bsrv = Main.getBenchServer();
        Server stub = (Server) bsrv.create(new ServerFactory());
        Node[] nodes = new Node[size];
        for (int i = 0; i < size; i++)
            nodes[i] = new Node(null, 0);

        long start = System.currentTimeMillis();
        for (int i = 0; i < reps; i++)
            stub.call(nodes);
        long time = System.currentTimeMillis() - start;

        bsrv.unexport(stub, true);
        return time;
    }
}
