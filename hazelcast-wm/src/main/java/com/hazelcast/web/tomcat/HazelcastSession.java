/*
 * Copyright (c) 2008-2012, Hazel Bilisim Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.web.tomcat;

import com.hazelcast.core.IMap;
import com.hazelcast.query.SqlPredicate;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.Enumerator;

import javax.servlet.http.*;
import java.io.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author ali
 */

public class HazelcastSession extends StandardSession {

    static final String SESSION_MARK = "__hz_ses_mark";
    private static final String SESSION_MARK_EXCEPTION = "'" + SESSION_MARK + "' is a reserved key for Hazelcast!";

    /**
     * Construct a new Session associated with the specified Manager.
     *
     * @param manager The manager with which this Session is associated
     */
    public HazelcastSession(Manager manager) {
        super(manager);
    }

    /**
     * Descriptive information describing this Session implementation.
     */
    protected static final String info = "HazelcastSession/1.0";

    /**
     * Return the <code>HttpSession</code> for which this object
     * is the facade.
     */
    public HttpSession getSession() {
        if (facade == null) {
            if (SecurityUtil.isPackageProtectionEnabled()) {
                final HazelcastSession fsession = this;
                facade = (HazelcastSessionFacade) AccessController.doPrivileged(new PrivilegedAction() {
                    public Object run() {
                        return new HazelcastSessionFacade(fsession);
                    }
                });
            } else {
                facade = new HazelcastSessionFacade(this);
            }
        }
        return (facade);
    }

    /**
     * Return the object bound with the specified name in this session, or
     * <code>null</code> if no object is bound with that name.
     *
     * @param name Name of the attribute to be returned
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     */
    public Object getAttribute(String name) {
        if (!isValidInternal()) {
            throw new IllegalStateException
                    (sm.getString("standardSession.getAttribute.ise"));
        }
        // Name cannot be null
        if (name == null)
            throw new IllegalArgumentException
                    (sm.getString("standardSession.setAttribute.namenull"));
        if (SESSION_MARK.equals(name)) {
            throw new IllegalArgumentException(SESSION_MARK_EXCEPTION);
        }
        HazelcastAttribute attribute = (HazelcastAttribute) attributes.get(name);
        if (attribute == null) {
            final IMap<String, HazelcastAttribute> sessionMap = HazelcastClusterSupport.get().getAttributesMap();
            attribute = (HazelcastAttribute) sessionMap.get(getIdInternal() + "_" + name);
            if (attribute == null) {
                attributes.put(name, new HazelcastAttribute(getIdInternal(), name, null));
                return null;
            }
        }
        long requestId = LocalRequestId.get();
        attribute.touch(requestId);
        return attribute.getValue();
    }

    /**
     * Get HazelAttribute directly
     */
    public Object getLocalAttribute(String name) {
        return super.getAttribute(name);
    }

    /**
     * Bind an object to this session, using the specified name.  If an object
     * of the same name is already bound to this session, the object is
     * replaced.
     * <p/>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueBound()</code> on the object.
     *
     * @param name   Name to which the object is bound, cannot be null
     * @param value  Object to be bound, cannot be null
     * @param notify whether to notify session listeners
     * @throws IllegalArgumentException if an attempt is made to add a
     *                                  non-serializable object in an environment marked distributable.
     * @throws IllegalStateException    if this method is called on an
     *                                  invalidated session
     */

    public void setAttribute(String name, Object value, boolean notify) {
        // Name cannot be null
        if (name == null)
            throw new IllegalArgumentException
                    (sm.getString("standardSession.setAttribute.namenull"));
        if (SESSION_MARK.equals(name)) {
            throw new IllegalArgumentException(SESSION_MARK_EXCEPTION);
        }
        // Null value is the same as removeAttribute()
        if (value == null) {
            removeAttribute(name);
            return;
        }
        // Validate our current state
        if (!isValidInternal())
            throw new IllegalStateException
                    (sm.getString("standardSession.setAttribute.ise"));
        if ((manager != null) && manager.getDistributable() &&
                !(value instanceof Serializable))
            throw new IllegalArgumentException
                    (sm.getString("standardSession.setAttribute.iae"));
        // Construct an event with the new value
        HttpSessionBindingEvent event = null;
        // Call the valueBound() method if necessary
        if (notify && value instanceof HttpSessionBindingListener) {
            HazelcastAttribute oldAttribute = (HazelcastAttribute) attributes.get(name);
            // Don't call any notification if replacing with the same value
            if (oldAttribute != null && value != oldAttribute.getValue()) {
                event = new HttpSessionBindingEvent(getSession(), name, value);
                try {
                    ((HttpSessionBindingListener) value).valueBound(event);
                } catch (Throwable t) {
                    manager.getContainer().getLogger().error
                            (sm.getString("standardSession.bindingEvent"), t);
                }
            }
        }
        final HazelcastAttribute attribute = new HazelcastAttribute(getIdInternal(), name, value);
        long requestId = LocalRequestId.get();
        attribute.touch(requestId);
        final HazelcastAttribute unboundAttribute = (HazelcastAttribute) attributes.put(name, attribute);
        final Object unboundValue = unboundAttribute != null ? unboundAttribute.getValue() : null;
        // Call the valueUnbound() method if necessary
        if (notify && (unboundValue != null) && (unboundValue != value) &&
                (unboundValue instanceof HttpSessionBindingListener)) {
            try {
                ((HttpSessionBindingListener) unboundValue).valueUnbound
                        (new HttpSessionBindingEvent(getSession(), name));
            } catch (Throwable t) {
                manager.getContainer().getLogger().error
                        (sm.getString("standardSession.bindingEvent"), t);
            }
        }
        if (!notify) return;
        // Notify interested application event listeners
        Context context = (Context) manager.getContainer();
        Object listeners[] = context.getApplicationEventListeners();
        if (listeners == null)
            return;
        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof HttpSessionAttributeListener))
                continue;
            HttpSessionAttributeListener listener =
                    (HttpSessionAttributeListener) listeners[i];
            try {
                if (unboundValue != null) {
                    fireContainerEvent(context,
                            "beforeSessionAttributeReplaced",
                            listener);
                    if (event == null) {
                        event = new HttpSessionBindingEvent
                                (getSession(), name, unboundValue);
                    }
                    listener.attributeReplaced(event);
                    fireContainerEvent(context,
                            "afterSessionAttributeReplaced",
                            listener);
                } else {
                    fireContainerEvent(context,
                            "beforeSessionAttributeAdded",
                            listener);
                    if (event == null) {
                        event = new HttpSessionBindingEvent
                                (getSession(), name, value);
                    }
                    listener.attributeAdded(event);
                    fireContainerEvent(context,
                            "afterSessionAttributeAdded",
                            listener);
                }
            } catch (Throwable t) {
                try {
                    if (unboundValue != null) {
                        fireContainerEvent(context,
                                "afterSessionAttributeReplaced",
                                listener);
                    } else {
                        fireContainerEvent(context,
                                "afterSessionAttributeAdded",
                                listener);
                    }
                } catch (Exception e) {
                    ;
                }
                manager.getContainer().getLogger().error
                        (sm.getString("standardSession.attributeEvent"), t);
            }
        }
    }

    /**
     * Remove the object bound with the specified name from this session.  If
     * the session does not have an object bound with this name, this method
     * does nothing.
     * <p/>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueUnbound()</code> on the object.
     *
     * @param name   Name of the object to remove from this session.
     * @param notify Should we notify interested listeners that this
     *               attribute is being removed?
     */
    protected void removeAttributeInternal(String name, boolean notify) {
        // Avoid NPE
        if (name == null) return;
        if (name.equals(SESSION_MARK)) {
            throw new IllegalArgumentException(SESSION_MARK_EXCEPTION);
        }
        // Remove this attribute from our collection
        HazelcastAttribute attribute = (HazelcastAttribute) attributes.remove(name);
        if (attribute == null || attribute.getValue() == null) {
            return;
        }
        Object value = attribute.getValue();
        attribute.setValue(null);
        long requestId = LocalRequestId.get();
        attribute.touch(requestId);
        attributes.put(name, attribute);
        notifyRemove(name, value, notify);
    }

    protected void removeAttributeHard(String name, boolean notify) {
        // Avoid NPE
        if (name == null) return;
        // Remove this attribute from our collection
        HazelcastAttribute attribute = (HazelcastAttribute) attributes.remove(name);
        if (attribute == null) {
            return;
        }
        Object value = attribute.getValue();
        final IMap<String, HazelcastAttribute> sessionAttrMap = HazelcastClusterSupport.get().getAttributesMap();
        sessionAttrMap.remove(attribute.getKey());
        notifyRemove(name, value, notify);
    }

    protected void notifyRemove(String name, Object value, boolean notify) {
        // Do we need to do valueUnbound() and attributeRemoved() notification?
        if (!notify || (value == null)) {
            return;
        }
        // Call the valueUnbound() method if necessary
        HttpSessionBindingEvent event = null;
        if (value instanceof HttpSessionBindingListener) {
            event = new HttpSessionBindingEvent(getSession(), name, value);
            ((HttpSessionBindingListener) value).valueUnbound(event);
        }
        // Notify interested application event listeners
        Context context = (Context) manager.getContainer();
        Object listeners[] = context.getApplicationEventListeners();
        if (listeners == null)
            return;
        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof HttpSessionAttributeListener))
                continue;
            HttpSessionAttributeListener listener =
                    (HttpSessionAttributeListener) listeners[i];
            try {
                fireContainerEvent(context,
                        "beforeSessionAttributeRemoved",
                        listener);
                if (event == null) {
                    event = new HttpSessionBindingEvent
                            (getSession(), name, value);
                }
                listener.attributeRemoved(event);
                fireContainerEvent(context,
                        "afterSessionAttributeRemoved",
                        listener);
            } catch (Throwable t) {
                try {
                    fireContainerEvent(context,
                            "afterSessionAttributeRemoved",
                            listener);
                } catch (Exception e) {
                    ;
                }
                manager.getContainer().getLogger().error
                        (sm.getString("standardSession.attributeEvent"), t);
            }
        }
    }

    /**
     * Perform internal processing required to activate this
     * session.
     */
    public void activate() {
        // Initialize access count
        if (ACTIVITY_CHECK) {
            accessCount = new AtomicInteger();
        }
        // Notify interested session event listeners
        fireSessionEvent(Session.SESSION_ACTIVATED_EVENT, null);
        // Notify ActivationListeners
        HttpSessionEvent event = null;
        String keys[] = keys();
        for (int i = 0; i < keys.length; i++) {
            Object attribute = ((HazelcastAttribute) attributes.get(keys[i])).getValue();
            if (attribute instanceof HttpSessionActivationListener) {
                if (event == null)
                    event = new HttpSessionEvent(getSession());
                try {
                    ((HttpSessionActivationListener) attribute)
                            .sessionDidActivate(event);
                } catch (Throwable t) {
                    manager.getContainer().getLogger().error
                            (sm.getString("standardSession.attributeEvent"), t);
                }
            }
        }
    }

    /**
     * Perform the internal processing required to passivate
     * this session.
     */
    public void passivate() {
        // Notify interested session event listeners
        fireSessionEvent(Session.SESSION_PASSIVATED_EVENT, null);
        // Notify ActivationListeners
        HttpSessionEvent event = null;
        String keys[] = keys();
        for (int i = 0; i < keys.length; i++) {
            Object attribute = ((HazelcastAttribute) attributes.get(keys[i])).getValue();
            if (attribute instanceof HttpSessionActivationListener) {
                if (event == null)
                    event = new HttpSessionEvent(getSession());
                try {
                    ((HttpSessionActivationListener) attribute)
                            .sessionWillPassivate(event);
                } catch (Throwable t) {
                    manager.getContainer().getLogger().error
                            (sm.getString("standardSession.attributeEvent"), t);
                }
            }
        }
    }

    /**
     * Read a serialized version of this session object from the specified
     * object input stream.
     * <p/>
     * <b>IMPLEMENTATION NOTE</b>:  The reference to the owning Manager
     * is not restored by this method, and must be set explicitly.
     *
     * @param stream The input stream to read from
     * @throws ClassNotFoundException if an unknown class is specified
     * @throws IOException            if an input/output error occurs
     */
    protected void readObject(ObjectInputStream stream)
            throws ClassNotFoundException, IOException {
        // Deserialize the scalar instance variables (except Manager)
        authType = null;        // Transient only
        creationTime = ((Long) stream.readObject()).longValue();
        lastAccessedTime = ((Long) stream.readObject()).longValue();
        maxInactiveInterval = ((Integer) stream.readObject()).intValue();
        isNew = ((Boolean) stream.readObject()).booleanValue();
        isValid = ((Boolean) stream.readObject()).booleanValue();
        thisAccessedTime = ((Long) stream.readObject()).longValue();
        principal = null;        // Transient only
        //        setId((String) stream.readObject());
        id = (String) stream.readObject();
        if (manager.getContainer().getLogger().isDebugEnabled())
            manager.getContainer().getLogger().debug
                    ("readObject() loading session " + id);
        // Deserialize the attribute count and attribute values
        if (attributes == null)
            attributes = new Hashtable();
        int n = ((Integer) stream.readObject()).intValue();
        boolean isValidSave = isValid;
        isValid = true;
        for (int i = 0; i < n; i++) {
            String name = (String) stream.readObject();
            Object value = (Object) stream.readObject();
            if ((value instanceof String) && (value.equals(NOT_SERIALIZED)))
                continue;
            if (manager.getContainer().getLogger().isDebugEnabled())
                manager.getContainer().getLogger().debug("  loading attribute '" + name +
                        "' with value '" + value + "'");
            attributes.put(name, new HazelcastAttribute(id, name, value));
        }
        attributes.put(SESSION_MARK, new HazelcastAttribute(id, SESSION_MARK, System.currentTimeMillis()));
        isValid = isValidSave;
        if (listeners == null) {
            listeners = new ArrayList();
        }
        if (notes == null) {
            notes = new Hashtable();
        }
    }

    /**
     * Write a serialized version of this session object to the specified
     * object output stream.
     * <p/>
     * <b>IMPLEMENTATION NOTE</b>:  The owning Manager will not be stored
     * in the serialized representation of this Session.  After calling
     * <code>readObject()</code>, you must set the associated Manager
     * explicitly.
     * <p/>
     * <b>IMPLEMENTATION NOTE</b>:  Any attribute that is not Serializable
     * will be unbound from the session, with appropriate actions if it
     * implements HttpSessionBindingListener.  If you do not want any such
     * attributes, be sure the <code>distributable</code> property of the
     * associated Manager is set to <code>true</code>.
     *
     * @param stream The output stream to write to
     * @throws IOException if an input/output error occurs
     */
    protected void writeObject(ObjectOutputStream stream) throws IOException {
        // Write the scalar instance variables (except Manager)
        stream.writeObject(new Long(creationTime));
        stream.writeObject(new Long(lastAccessedTime));
        stream.writeObject(new Integer(maxInactiveInterval));
        stream.writeObject(new Boolean(isNew));
        stream.writeObject(new Boolean(isValid));
        stream.writeObject(new Long(thisAccessedTime));
        stream.writeObject(id);
        if (manager.getContainer().getLogger().isDebugEnabled())
            manager.getContainer().getLogger().debug
                    ("writeObject() storing session " + id);
        // Accumulate the names of serializable and non-serializable attributes
        String keys[] = keys();
        ArrayList saveNames = new ArrayList();
        ArrayList saveValues = new ArrayList();
        for (int i = 0; i < keys.length; i++) {
            HazelcastAttribute hattribute = (HazelcastAttribute) attributes.get(keys[i]);
            if (hattribute == null)
                continue;
            Object value = hattribute.getValue();
            if (value == null)
                continue;
            else if ((value instanceof Serializable)
                    && (!exclude(keys[i]))) {
                saveNames.add(keys[i]);
                saveValues.add(value);
            } else {
                removeAttributeInternal(keys[i], true);
            }
        }
        // Serialize the attribute count and the Serializable attributes
        int n = saveNames.size();
        stream.writeObject(new Integer(n));
        for (int i = 0; i < n; i++) {
            stream.writeObject((String) saveNames.get(i));
            try {
                stream.writeObject(saveValues.get(i));
                if (manager.getContainer().getLogger().isDebugEnabled())
                    manager.getContainer().getLogger().debug
                            ("  storing attribute '" + saveNames.get(i) +
                                    "' with value '" + saveValues.get(i) + "'");
            } catch (NotSerializableException e) {
                manager.getContainer().getLogger().warn
                        (sm.getString("standardSession.notSerializable",
                                saveNames.get(i), id), e);
                stream.writeObject(NOT_SERIALIZED);
                if (manager.getContainer().getLogger().isDebugEnabled())
                    manager.getContainer().getLogger().debug
                            ("  storing attribute '" + saveNames.get(i) +
                                    "' with value NOT_SERIALIZED");
            }
        }
    }

    /**
     * Set the session identifier for this session.
     *
     * @param id The new session identifier
     */
    public void setId(String id) {
        if ((this.id != null) && (manager != null))
            manager.remove(this);
        this.id = id;
        final IMap<String, HazelcastAttribute> sessionAttrMap = HazelcastClusterSupport.get().getAttributesMap();
        Collection<HazelcastAttribute> colAttributes = sessionAttrMap.values(new SqlPredicate("sessionId=" + id));
        if (colAttributes.size() != 0) {
            for (HazelcastAttribute hattribute : colAttributes) {
                attributes.put(hattribute.getName(), hattribute);
            }
        } else {
            HazelcastAttribute mark = new HazelcastAttribute(id, SESSION_MARK, System.currentTimeMillis());
            sessionAttrMap.put(mark.getKey(), mark);
            attributes.put(SESSION_MARK, mark);
        }
        if (manager != null)
            manager.add(this);
        tellNew();
    }

    /**
     * Perform the internal processing required to invalidate this session,
     * without triggering an exception if the session has already expired.
     *
     * @param notify Should we notify listeners about the demise of
     *               this session?
     */
    public void expire(boolean notify) {
        // Mark this session as "being expired" if needed
        if (expiring)
            return;
        synchronized (this) {
            if (manager == null)
                return;
            expiring = true;
            // Notify interested application event listeners
            // FIXME - Assumes we call listeners in reverse order
            Context context = (Context) manager.getContainer();
            Object listeners[] = context.getApplicationLifecycleListeners();
            if (notify && (listeners != null)) {
                HttpSessionEvent event =
                        new HttpSessionEvent(getSession());
                for (int i = 0; i < listeners.length; i++) {
                    int j = (listeners.length - 1) - i;
                    if (!(listeners[j] instanceof HttpSessionListener))
                        continue;
                    HttpSessionListener listener =
                            (HttpSessionListener) listeners[j];
                    try {
                        fireContainerEvent(context,
                                "beforeSessionDestroyed",
                                listener);
                        listener.sessionDestroyed(event);
                        fireContainerEvent(context,
                                "afterSessionDestroyed",
                                listener);
                    } catch (Throwable t) {
                        try {
                            fireContainerEvent(context,
                                    "afterSessionDestroyed",
                                    listener);
                        } catch (Exception e) {
                            ;
                        }
                        manager.getContainer().getLogger().error
                                (sm.getString("standardSession.sessionEvent"), t);
                    }
                }
            }
            if (ACTIVITY_CHECK) {
                accessCount.set(0);
            }
            setValid(false);
            /*
            * Compute how long this session has been alive, and update
            * session manager's related properties accordingly
            */
            long timeNow = System.currentTimeMillis();
            int timeAlive = (int) ((timeNow - creationTime) / 1000);
            synchronized (manager) {
                if (timeAlive > manager.getSessionMaxAliveTime()) {
                    manager.setSessionMaxAliveTime(timeAlive);
                }
                int numExpired = manager.getExpiredSessions();
                numExpired++;
                manager.setExpiredSessions(numExpired);
                int average = manager.getSessionAverageAliveTime();
                average = ((average * (numExpired - 1)) + timeAlive) / numExpired;
                manager.setSessionAverageAliveTime(average);
            }
            // Remove this session from our manager's active sessions
            manager.remove(this);
            // Notify interested session event listeners
            if (notify) {
                fireSessionEvent(Session.SESSION_DESTROYED_EVENT, null);
            }
            // We have completed expire of this session
            expiring = false;
            // Unbind any objects associated with this session
            String keys[] = keys();
            for (int i = 0; i < keys.length; i++)
                removeAttributeHard(keys[i], notify);
            removeAttributeHard(SESSION_MARK, notify);
        }
    }

    /**
     * Return the names of all currently defined session attributes
     * as an array of Strings.  If there are no defined attributes, a
     * zero-length array is returned.
     */
    protected String[] keys() {
        Set keySet = attributes.keySet();
        keySet.remove(SESSION_MARK);
        return ((String[]) keySet.toArray(EMPTY_ARRAY));
    }

    /**
     * Return an <code>Enumeration</code> of <code>String</code> objects
     * containing the names of the objects bound to this session.
     *
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     */
    public Enumeration getAttributeNames() {
        if (!isValidInternal())
            throw new IllegalStateException
                    (sm.getString("standardSession.getAttributeNames.ise"));
        Set keySet = attributes.keySet();
        keySet.remove(SESSION_MARK);
        return (new Enumerator(keySet, true));
    }
}
