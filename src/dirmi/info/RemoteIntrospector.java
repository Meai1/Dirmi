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

package dirmi.info;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.cojen.classfile.MethodDesc;
import org.cojen.classfile.TypeDesc;
import org.cojen.util.WeakCanonicalSet;
import org.cojen.util.WeakIdentityMap;

import dirmi.Asynchronous;
import dirmi.Idempotent;

import dirmi.core.Identifier;

/**
 * Supports examination of Remote types, returning all metadata associated
 * with it. As part of the examination, all annotations are gathered up. All
 * examined data is cached, so repeat examinations are fast, unless the
 * examination failed.
 *
 * @author Brian S O'Neill
 */
public class RemoteIntrospector {
    private static final Map<Class<?>, RInfo> cInfoCache;
    private static final WeakCanonicalSet cParameterCache;

    static {
        cInfoCache = new WeakIdentityMap();
        cParameterCache = new WeakCanonicalSet();
    }

    static RParameter intern(RParameter param) {
        return (RParameter) cParameterCache.put(param);
    }

    /**
     * @param remote remote interface to examine
     * @throws IllegalArgumentException if remote is null or malformed
     */
    public static RemoteInfo examine(Class<? extends Remote> remote)
        throws IllegalArgumentException
    {
        if (remote == null) {
            throw new IllegalArgumentException("Remote interface must not be null");
        }

        synchronized (cInfoCache) {
            RInfo info = cInfoCache.get(remote);
            if (info != null) {
                return info;
            }

            if (!remote.isInterface()) {
                throw new IllegalArgumentException("Remote type must be an interface: " + remote);
            }

            if (!Modifier.isPublic(remote.getModifiers())) {
                throw new IllegalArgumentException
                    ("Remote interface must be public: " + remote.getName());
            }

            if (!Remote.class.isAssignableFrom(remote)) {
                throw new IllegalArgumentException
                    ("Remote interface must extend java.rmi.Remote: " + remote.getName());
            }

            Map<String, RMethod> methodMap = new LinkedHashMap<String, RMethod>();

            // FIXME: capture ResponseTimeout

            for (Method m : remote.getMethods()) {
                if (!m.getDeclaringClass().isInterface()) {
                    continue;
                }

                String key;
                {
                    Class<?>[] params = m.getParameterTypes();
                    TypeDesc[] paramDescs = new TypeDesc[params.length];
                    for (int i=0; i<params.length; i++) {
                        paramDescs[i] = TypeDesc.forClass(params[i]);
                    }
                    MethodDesc desc = MethodDesc.forArguments
                        (TypeDesc.forClass(m.getReturnType()), paramDescs);
                    key = m.getName() + ':' + desc;
                }

                if (!methodMap.containsKey(key)) {
                    methodMap.put(key, new RMethod(m));
                    continue;
                }

                RMethod existing = methodMap.get(key);
                RMethod candidate = new RMethod(existing.getMethodID(), m);
                
                if (existing.equals(candidate)) {
                    continue;
                }

                // Same method inherited from multiple parent interfaces. Only
                // exceptions are allowed to differ. If so, select the
                // intersection of the exceptions.
                candidate = existing.intersectExceptions(candidate);

                methodMap.put(key, candidate);
            }

            for (RMethod method : methodMap.values()) {
                if (!method.declaresException(RemoteException.class)) {
                    throw new IllegalArgumentException
                        ("Method must declare throwing " +
                         "java.rmi.RemoteException (or superclass): " +
                         method.methodDesc());
                }

                if (method.isAsynchronous()) {
                    if (method.getReturnType() != null) {
                        throw new IllegalArgumentException
                            ("Asynchronous method must return void: " + method.methodDesc());
                    }
                    for (RemoteParameter type : method.getExceptionTypes()) {
                        if (type.getSerializedType() != RemoteException.class) {
                            throw new IllegalArgumentException
                                ("Asynchronous method can only throw RemoteException: \"" +
                                 method.getSignature(remote.getName()) + '"');
                        }
                    }
                }
            }

            // FIXME: check asynchronous: no response timeout

            info = new RInfo(remote.getName(), new LinkedHashSet<RMethod>(methodMap.values()));
            cInfoCache.put(remote, info);

            // Now that RInfo is in the cache, call resolve to check remote
            // parameters.
            try {
                info.resolve();
            } catch (IllegalArgumentException e) {
                cInfoCache.remove(remote);
                throw e;
            }

            return info;
        }
    }

    private RemoteIntrospector() {
    }

    private static class RInfo implements RemoteInfo {
        private static final long serialVersionUID = 1L;

        private final Identifier mID;
        private final String mName;
        private final Set<RMethod> mMethods;

        private transient Map<String, Set<RMethod>> mMethodsByName;
        private transient Map<Identifier, RemoteMethod> mMethodMap;

        RInfo(String name, Set<RMethod> methods) {
            mID = Identifier.identify(this);
            mName = name;
            mMethods = Collections.unmodifiableSet(methods);
        }

        public String getName() {
            return mName;
        }

        public Identifier getRemoteID() {
            return mID;
        }

        public Set<? extends RemoteMethod> getRemoteMethods() {
            return mMethods;
        }

        public Set<? extends RemoteMethod> getRemoteMethods(String name) {
            if (mMethodsByName == null) {
                Map<String, Set<RMethod>> methodsByName = new HashMap<String, Set<RMethod>>();

                for (RMethod method : mMethods) {
                    String methodName = method.getName();
                    Set<RMethod> set = methodsByName.get(methodName);
                    if (set == null) {
                        set = new LinkedHashSet<RMethod>();
                        methodsByName.put(methodName, set);
                    }
                    set.add(method);
                }

                // Pass through again, making sure each contained set is unmodifiable.
                for (Map.Entry<String, Set<RMethod>> entry : methodsByName.entrySet()) {
                    entry.setValue(Collections.unmodifiableSet(entry.getValue()));
                }

                mMethodsByName = methodsByName;
            }

            Set<? extends RemoteMethod> methods = mMethodsByName.get(name);
            if (methods == null) {
                methods = Collections.emptySet();
            }
            return methods;
        }

        public RemoteMethod getRemoteMethod(String name, RemoteParameter... params)
            throws NoSuchMethodException
        {
            int paramsLength = params == null ? 0 : params.length;
            search:
            for (RemoteMethod method : getRemoteMethods(name)) {
                List<? extends RemoteParameter> paramTypes = method.getParameterTypes();
                if (paramTypes.size() == paramsLength) {
                    for (int i=0; i<paramsLength; i++) {
                        if (!paramTypes.get(i).equals(params[i])) {
                            continue search;
                        }
                    }
                    return method;
                }
            }
            throw new NoSuchMethodException(name);
        }

        public RemoteMethod getRemoteMethod(Identifier methodID) throws NoSuchMethodException {
            if (mMethodMap == null) {
                Map methodMap = new HashMap();
                for (RMethod method : mMethods) {
                    methodMap.put(method.getMethodID(), method);
                }
                mMethodMap = methodMap;
            }
            RemoteMethod method = (RemoteMethod) mMethodMap.get(methodID);
            if (method == null) {
                throw new NoSuchMethodException("methodID: " + methodID);
            }
            return method;
        }

        @Override
        public int hashCode() {
            return mName.hashCode() + mID.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RInfo) {
                RInfo other = (RInfo) obj;
                return mName.equals(other.mName) && (mID == other.mID) &&
                    getRemoteMethods().equals(other.getRemoteMethods());
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append("RemoteInfo {id=");
            b.append(mID);
            b.append(", name=");
            b.append(mName);
            b.append('}');
            return b.toString();
        }

        void resolve() {
            for (RMethod method : mMethods) {
                method.resolve();
            }
        }
    }

    private static class RMethod implements RemoteMethod {
        private static final long serialVersionUID = 1L;

        private final Identifier mID;
        private final String mName;
        private RemoteParameter mReturnType;
        private List<RParameter> mParameterTypes;
        private final Set<RemoteParameter> mExceptionTypes;

        private final boolean mAsynchronous;
        private final boolean mIdempotent;
        private final long mResponseTimeout;

        private transient Method mMethod;

        RMethod(Method m) {
            this(null, m);
        }

        RMethod(Identifier id, Method m) {
            if (id == null) {
                id = Identifier.identify(this);
            }
            mID = id;
            mName = m.getName();

            // First pass, treat all params as serialized. Resolve on second pass.
            // This allows remote methods to pass instances of declaring class without
            // causing the introspector to overflow the stack.

            Class<?> returnType = m.getReturnType();
            if (returnType == null) {
                mReturnType = null;
            } else {
                mReturnType = RParameter.makeTemp(returnType);
            }

            Class<?>[] paramsTypes = m.getParameterTypes();
            if (paramsTypes == null || paramsTypes.length == 0) {
                mParameterTypes = null;
            } else {
                mParameterTypes = new ArrayList<RParameter>(paramsTypes.length);
                for (Class<?> paramType : paramsTypes) {
                    mParameterTypes.add(RParameter.makeTemp(paramType));
                }
            }

            Class<?>[] exceptionTypes = m.getExceptionTypes();
            if (exceptionTypes == null || exceptionTypes.length == 0) {
                mExceptionTypes = null;
            } else {
                Set<RemoteParameter> set = new LinkedHashSet<RemoteParameter>();
                for (Class<?> exceptionType : exceptionTypes) {
                    set.add(RParameter.makeTemp(exceptionType));
                }
                mExceptionTypes = Collections.unmodifiableSet(set);
            }

            mAsynchronous = m.getAnnotation(Asynchronous.class) != null;
            mIdempotent = m.getAnnotation(Idempotent.class) != null;
            // FIXME: capture ResponseTimeout
            mResponseTimeout = -1;

            // Hang on to this until resolve is called.
            mMethod = m;
        }

        private RMethod(RMethod existing, Set<RemoteParameter> exceptionTypes) {
            mID = existing.mID;
            mName = existing.mName;
            mReturnType = existing.mReturnType;
            mParameterTypes = existing.mParameterTypes;
            mExceptionTypes = Collections.unmodifiableSet(exceptionTypes);

            mAsynchronous = existing.mAsynchronous;
            mIdempotent = existing.mIdempotent;
            mResponseTimeout = existing.mResponseTimeout;

            mMethod = existing.mMethod;
        }

        private static <E> List<E> unfixList(List<E> list) {
            if (list == null) {
                list = Collections.emptyList();
            }
            return list;
        }

        public String getName() {
            return mName;
        }

        public Identifier getMethodID() {
            return mID;
        }

        public RemoteParameter getReturnType() {
            return mReturnType;
        }

        public List<? extends RemoteParameter> getParameterTypes() {
            if (mParameterTypes == null) {
                return Collections.emptyList();
            }
            return mParameterTypes;
        }

        public Set<? extends RemoteParameter> getExceptionTypes() {
            if (mExceptionTypes == null) {
                return Collections.emptySet();
            }
            return mExceptionTypes;
        }

        public boolean isAsynchronous() {
            return mAsynchronous;
        }

        public boolean isIdempotent() {
            return mIdempotent;
        }

        public long getResponseTimeoutMillis() {
            return mResponseTimeout;
        }

        @Override
        public int hashCode() {
            return mName.hashCode() + mID.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RMethod) {
                RMethod other = (RMethod) obj;
                return mName.equals(other.mName) && (mID == other.mID) &&
                    getParameterTypes().equals(other.getParameterTypes()) &&
                    getExceptionTypes().equals(other.getExceptionTypes()) &&
                    (mAsynchronous == other.mAsynchronous) &&
                    (mIdempotent == other.mIdempotent) &&
                    (mResponseTimeout == other.mResponseTimeout);
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append("RemoteMethod {id=");
            b.append(mID);
            b.append(", sig=\"");
            b.append(getSignature(null));
            b.append('"');
            b.append('}');
            return b.toString();
        }

        /**
         * @param className optional
         */
        String getSignature(String className) {
            StringBuilder b = new StringBuilder();
            b.append(getReturnType() == null ? "void" : getReturnType());
            b.append(' ');
            if (className != null) {
                b.append(className);
                b.append('.');
            }
            b.append(getName());

            b.append('(');
            int count = 0;
            for (RemoteParameter param : getParameterTypes()) {
                if (count++ > 0) {
                    b.append(", ");
                }
                b.append(param);
            }
            b.append(')');

            Set<? extends RemoteParameter> exceptions = getExceptionTypes();
            if (exceptions.size() > 0) {
                b.append(" throws ");
                count = 0;
                for (RemoteParameter param : exceptions) {
                    if (count++ > 0) {
                        b.append(", ");
                    }
                    b.append(param);
                }
            }

            return b.toString();
        }

        RMethod intersectExceptions(RMethod other) {
            if (this == other) {
                return this;
            }
            if (!mName.equals(other.mName)) {
                // This indicates a bug in RemoteIntrospector.
                throw new IllegalArgumentException("name mismatch");
            }
            if (mID != other.mID) {
                // This indicates a bug in RemoteIntrospector.
                throw new IllegalArgumentException("id mismatch");
            }
            if (!getParameterTypes().equals(other.getParameterTypes())) {
                // This indicates a bug in RemoteIntrospector.
                throw new IllegalArgumentException("parameter types mismatch");
            }

            if (mIdempotent != other.mIdempotent) {
                // This is user error.
                throw new IllegalArgumentException
                    ("Inherited methods conflict in use of @Idempotent annotation: " +
                     methodDesc() + " and " + other.methodDesc());
            }

            if (mAsynchronous != other.mAsynchronous) {
                // This is user error.
                throw new IllegalArgumentException
                    ("Inherited methods conflict in use of @Asynchronous annotation: " +
                     methodDesc() + " and " + other.methodDesc());
            }

            if (mResponseTimeout != other.mResponseTimeout) {
                // This is user error.
                throw new IllegalArgumentException
                    ("Inherited methods conflict in use of @ResponseTimeout annotation: " +
                     methodDesc() + " and " + other.methodDesc());
            }

            Set<RemoteParameter> subset = new LinkedHashSet<RemoteParameter>();

            for (RemoteParameter exceptionType : mExceptionTypes) {
                if (other.declaresException(exceptionType)) {
                    subset.add(exceptionType);
                }
            }

            for (RemoteParameter exceptionType : other.mExceptionTypes) {
                if (this.declaresException(exceptionType)) {
                    subset.add(exceptionType);
                }
            }

            return new RMethod(this, subset);
        }

        boolean declaresException(RemoteParameter exceptionType) {
            return declaresException(exceptionType.getSerializedType());
        }

        boolean declaresException(Class<?> exceptionType) {
            if (mExceptionTypes == null) {
                return false;
            }
            for (RemoteParameter declared : mExceptionTypes) {
                if (declared.getSerializedType().isAssignableFrom(exceptionType)) {
                    return true;
                }
            }
            return false;
        }

        void resolve() {
            if (mReturnType != null) {
                Class<?> type = mReturnType.getSerializedType();
                if (Remote.class.isAssignableFrom(type)) {
                    mReturnType = RParameter.make
                        (examine((Class<Remote>) type), mReturnType.getRemoteDimensions(),
                         null, mReturnType.isUnshared());
                } else {
                    mReturnType = RParameter.make(null, 0, type, mReturnType.isUnshared());
                }
            }

            if (mParameterTypes != null) {
                int size = mParameterTypes.size();

                // If any individual parameter cannot be unshared, then none
                // can be unshared. This is because a complex serialized object
                // might refer to any parameter or even itself.
                boolean noneUnshared = false;
                for (int i=0; i<size; i++) {
                    if (!mParameterTypes.get(i).isUnshared()) {
                        noneUnshared = true;
                        break;
                    }
                }

                for (int i=0; i<size; i++) {
                    RemoteParameter param = mParameterTypes.get(i);
                    Class<?> type = param.getSerializedType();

                    boolean unshared = !noneUnshared && param.isUnshared();
                    // Can only be truly unshared if no other parameter is of same type.
                    if (unshared) {
                        for (int j=i+1; j<size; j++) {
                            RParameter jp = mParameterTypes.get(j);
                            if (type == jp.getSerializedType()) {
                                unshared = false;
                                // Mark parameter as unshared for when we see it again.
                                mParameterTypes.set(j, jp.toUnshared(false));
                                break;
                            }
                        }
                    }

                    int dimensions = param.getRemoteDimensions();
                    if (Remote.class.isAssignableFrom(type)) {
                        mParameterTypes.set
                            (i, RParameter.make
                             (examine((Class<Remote>) type), dimensions, null, unshared));
                    } else {
                        if (dimensions > 0) {
                            TypeDesc desc = TypeDesc.forClass(type);
                            while (--dimensions >= 0) {
                                desc = desc.toArrayType();
                            }
                            type = desc.toClass();
                        }
                        mParameterTypes.set(i, RParameter.make(null, 0, type, unshared));
                    }
                }

                mParameterTypes = Collections.unmodifiableList(mParameterTypes);
            }

            // Won't need this again.
            mMethod = null;
        }

        String methodDesc() {
            String name = mMethod.getDeclaringClass().getName() + '.' + mMethod.getName();
            return '"' + MethodDesc.forMethod(mMethod).toMethodSignature(name) + '"';
        }
    }

    private static class RParameter implements RemoteParameter {
        private static final long serialVersionUID = 1L;

        static RParameter makeTemp(Class<?> serializedType) {
            if (serializedType == void.class) {
                serializedType = null;
            }
            if (serializedType == null) {
                return null;
            }
            int dimensions = 0;
            while (serializedType.isArray()) {
                dimensions++;
                serializedType = serializedType.getComponentType();
            }

            boolean unshared = serializedType.isPrimitive() ||
                Remote.class.isAssignableFrom(serializedType) ||
                String.class.isAssignableFrom(serializedType) ||
                TypeDesc.forClass(serializedType).toPrimitiveType() != null;

            return intern(new RParameter(null, dimensions, serializedType, unshared));
        }

        static RParameter make(RemoteInfo remoteInfoType, int dimensions,
                               Class<?> serializedType,
                               boolean unshared)
        {
            if (serializedType == void.class) {
                serializedType = null;
            }
            if (remoteInfoType == null && serializedType == null) {
                return null;
            }
            return intern(new RParameter(remoteInfoType, dimensions, serializedType, unshared));
        }

        private final RemoteInfo mRemoteInfoType;
        private final int mDimensions;
        private final Class<?> mSerializedType;
        private final boolean mUnshared;

        private RParameter(RemoteInfo remoteInfoType, int dimensions,
                           Class<?> serializedType,
                           boolean unshared)
        {
            mRemoteInfoType = remoteInfoType;
            mDimensions = dimensions;
            mSerializedType = serializedType;
            mUnshared = unshared;
        }

        public boolean isUnshared() {
            return mUnshared;
        }

        public boolean isRemote() {
            return mRemoteInfoType != null;
        }

        public int getRemoteDimensions() {
            return mDimensions;
        }

        public RemoteInfo getRemoteInfoType() {
            return mRemoteInfoType;
        }

        public Class<?> getSerializedType() {
            return mSerializedType;
        }

        @Override
        public int hashCode() {
            if (mRemoteInfoType != null) {
                return mRemoteInfoType.hashCode() + mDimensions;
            }
            if (mSerializedType != null) {
                return mSerializedType.hashCode();
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RParameter) {
                RParameter other = (RParameter) obj;
                return ((mRemoteInfoType == null) ? (other.mRemoteInfoType == null) :
                        (mRemoteInfoType.equals(other.mRemoteInfoType))) &&
                    (mDimensions == other.mDimensions) &&
                    (mUnshared == other.mUnshared) &&
                    ((mSerializedType == null) ? (other.mSerializedType == null) :
                     (mSerializedType.equals(other.mSerializedType)));
            }
            return false;
        }

        @Override
        public String toString() {
            if (mRemoteInfoType != null) {
                return mRemoteInfoType.getName();
            }
            if (mSerializedType != null) {
                return TypeDesc.forClass(mSerializedType).getFullName();
            }
            return super.toString();
        }

        RParameter toUnshared(boolean unshared) {
            if (unshared == mUnshared) {
                return this;
            }
            return intern(new RParameter(mRemoteInfoType, mDimensions, mSerializedType, unshared));
        }

        Object readResolve() throws java.io.ObjectStreamException {
            return intern(this);
        }
    }
}