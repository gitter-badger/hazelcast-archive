/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
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
 *
 */

package com.hazelcast.spring;

import com.hazelcast.config.*;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.*;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.lang.reflect.Method;
import java.util.*;

public class HazelcastConfigBeanDefinitionParser extends AbstractBeanDefinitionParser  {

    protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
        final SpringXmlConfigBuilder springXmlConfigBuilder = new SpringXmlConfigBuilder(parserContext);
        springXmlConfigBuilder.handleConfig(element);
        return springXmlConfigBuilder.getBeanDefinition();
    }
    
    private static class SpringXmlConfigBuilder extends AbstractXmlConfigHelper {

        private final ParserContext parserContext;
        
        private BeanDefinitionBuilder configBuilder;
        
        private ManagedMap mapConfigManagedMap;
        private ManagedMap queueManagedMap;
        private ManagedMap topicManagedMap;
        private ManagedMap executorManagedMap;
        private ManagedMap wanReplicationManagedMap;
        private ManagedMap mergePolicyConfigMap;
        
        final Map<String, Integer> counter = new HashMap<String, Integer>();

        public SpringXmlConfigBuilder(ParserContext parserContext) {
            this.parserContext = parserContext;
            this.configBuilder = BeanDefinitionBuilder.rootBeanDefinition(Config.class);
            
            this.mapConfigManagedMap = new ManagedMap();
            this.queueManagedMap = new ManagedMap();
            this.topicManagedMap = new ManagedMap();
            this.executorManagedMap = new ManagedMap();
            this.wanReplicationManagedMap = new ManagedMap();
            this.mergePolicyConfigMap = new ManagedMap();
            
            this.configBuilder.addPropertyValue("mapConfigs", mapConfigManagedMap);
            this.configBuilder.addPropertyValue("QConfigs", queueManagedMap);
            this.configBuilder.addPropertyValue("topicConfigs", topicManagedMap);
            this.configBuilder.addPropertyValue("executorConfigMap", executorManagedMap);
            this.configBuilder.addPropertyValue("wanReplicationConfigs", wanReplicationManagedMap);
            this.configBuilder.addPropertyValue("mergePolicyConfigs", mergePolicyConfigMap);
        }
        
        public AbstractBeanDefinition getBeanDefinition(){
            return configBuilder.getBeanDefinition();
        }
        
        private String nextId(final String id){
            Integer idx = counter.get(id);
            idx = (idx != null ? idx.intValue() : 0) + 1;
            counter.put(id, idx);
            return id + idx;
        }
        
        protected BeanDefinitionBuilder createBeanBuilder(final Class clazz, final String id) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(clazz);
            
            final AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, nextId(id));
            
            BeanDefinitionReaderUtils.registerBeanDefinition(holder, parserContext.getRegistry());
            return builder;
        }
        
        protected BeanDefinitionBuilder createAndFillBeanBuilder(Node node, final Class clazz,
                final String propertyName,
                final BeanDefinitionBuilder parent,
                final String ... exceptPropertyNames) {
            BeanDefinitionBuilder builder = createBeanBuilder(clazz, propertyName);
            final AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            fillValues(node, builder, exceptPropertyNames);
            parent.addPropertyValue(propertyName, beanDefinition);
            return builder;
        }
        
        protected void createAndFillListedBean(Node node, final Class clazz,
                final String propertyName, final ManagedMap managedMap) {
            BeanDefinitionBuilder builder = createBeanBuilder(clazz, propertyName);
            final AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            
            final Node attName = node.getAttributes().getNamedItem("name");
            
            final String name = getValue(attName);
            builder.addPropertyValue("name", name);

            fillValues(node, builder);
            
            managedMap.put(name, beanDefinition);
        }
        
        protected void fillValues(Node node, BeanDefinitionBuilder builder, String ... excludeNames) {
            Collection<String> epn = excludeNames != null && excludeNames.length > 0 ?
                    new HashSet<String>(Arrays.asList(excludeNames)) : null;
            fillAttributeValues(node, builder, epn);
            for (org.w3c.dom.Node n : new IterableNodeList(node, Node.ELEMENT_NODE)) {
                String name = xmlToJavaName(cleanNodeName(n));
                if (epn != null && epn.contains(name)) continue;
                String value = getValue(n);
                builder.addPropertyValue(name, value);
            }
        }

        private void fillAttributeValues(Node node,
                BeanDefinitionBuilder builder, Collection<String> epn) {
            final NamedNodeMap atts = node.getAttributes();
            if (atts != null) {
                for (int a = 0; a < atts.getLength(); a++) {
                    final org.w3c.dom.Node att = atts.item(a);
                    final String name = xmlToJavaName(att.getNodeName());
                    if (epn != null && epn.contains(name)) continue;
                    final String value = att.getNodeValue();
                    builder.addPropertyValue(name, value);
                }
            }
        }
        
        public void handleConfig(final Element docElement) {
            for (org.w3c.dom.Node node : new IterableNodeList(docElement, Node.ELEMENT_NODE)) {
                // handleViaReflection(node);
                final String nodeName = cleanNodeName(node.getNodeName());
                if ("network".equals(nodeName)) {
                    handleNetwork(node);
                } else if ("group".equals(nodeName)) {
                    handleGroup(node);
                } else if ("properties".equals(nodeName)) {
                    handleProperties(node);
                } else if ("executor-service".equals(nodeName)) {
                    handleExecutor(node);
                } else if ("queue".equals(nodeName)) {
                    handleQueue(node);
                } else if ("map".equals(nodeName)) {
                    handleMap(node);
                } else if ("topic".equals(nodeName)) {
                    handleTopic(node);
                } else if ("merge-policies".equals(nodeName)) {
                    handleMergePolicies(node);
                } else if ("wan-replication".equals(nodeName)) {
                	handleWanReplication(node);
                }
            }
        }

        public void handleNetwork(Node node) {
            BeanDefinitionBuilder networkConfigBuilder = createBeanBuilder(NetworkConfig.class, "networkConfig");
            final AbstractBeanDefinition beanDefinition = networkConfigBuilder.getBeanDefinition();
            
            fillAttributeValues(node, configBuilder, null);
            
            for (org.w3c.dom.Node child : new IterableNodeList(node, Node.ELEMENT_NODE)) {
                final String nodeName = cleanNodeName(child);
                if ("join".equals(nodeName)) {
                    handleJoin(child, networkConfigBuilder);
                } else if ("interfaces".equals(nodeName)) {
                    handleInterfaces(child, networkConfigBuilder);
                } else if ("symmetric-encryption".equals(nodeName)) {
                    handleSymmetricEncryption(child, networkConfigBuilder);
                } else if ("asymmetric-encryption".equals(nodeName)) {
                    handleAsymmetricEncryption(child, networkConfigBuilder);
                }
            }
            
            configBuilder.addPropertyValue("networkConfig", beanDefinition);
        }

        protected void handleViaReflection(org.w3c.dom.Node child) {
            final String methodName = xmlToJavaName("handle-" + cleanNodeName(child));
            final Method method;
            try {
                method = getClass().getMethod(methodName, new Class[]{org.w3c.dom.Node.class});
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
                return;
            }
            try {
                method.invoke(this, new Object[]{child});
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void handleGroup(Node node) {
            createAndFillBeanBuilder(node, GroupConfig.class, "groupConfig", configBuilder);
        }
        
        public void handleProperties(Node node) {
            handleProperties(node, configBuilder);
        }

        public void handleInterfaces(Node node, final BeanDefinitionBuilder networkConfigBuilder) {
            BeanDefinitionBuilder builder = createBeanBuilder(Interfaces.class, "interfaces");
            final AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            
            final NamedNodeMap atts = node.getAttributes();
            if (atts != null) {
                for (int a = 0; a < atts.getLength(); a++) {
                    final org.w3c.dom.Node att = atts.item(a);
                    final String name = xmlToJavaName(att.getNodeName());
                    final String value = att.getNodeValue();
                    builder.addPropertyValue(name, value);
                }
            }
            ManagedList interfacesSet = new ManagedList();
            for (org.w3c.dom.Node n : new IterableNodeList(node, Node.ELEMENT_NODE)) {
                String name = xmlToJavaName(cleanNodeName(n));
                String value = getValue(n);
                if ("interface".equals(name)){
                    interfacesSet.add(value);
                }
            }
            builder.addPropertyValue("interfaces", interfacesSet);
            
            networkConfigBuilder.addPropertyValue("interfaces", beanDefinition);
        }

        public void handleJoin(Node node, BeanDefinitionBuilder networkConfigBuilder) {
            BeanDefinitionBuilder joinConfigBuilder = createBeanBuilder(Join.class, "join");
            final AbstractBeanDefinition beanDefinition = joinConfigBuilder.getBeanDefinition();
            
            for (org.w3c.dom.Node child : new IterableNodeList(node, Node.ELEMENT_NODE)) {
                final String name = cleanNodeName(child);
                if ("multicast".equals(name)) {
                    handleMulticast(child, joinConfigBuilder);
                } else if ("tcp-ip".equals(name)) {
                    handleTcpIp(child, joinConfigBuilder);
                } else if ("aws".equals(name)) {
                    handleAws(child, joinConfigBuilder);
                }
            }
            
            networkConfigBuilder.addPropertyValue("join", beanDefinition);
        }
        
        public void handleAsymmetricEncryption(Node node, BeanDefinitionBuilder networkConfigBuilder) {
            createAndFillBeanBuilder(node, AsymmetricEncryptionConfig.class, "asymmetricEncryptionConfig", networkConfigBuilder);
        }
        
        public void handleSymmetricEncryption(Node node, BeanDefinitionBuilder networkConfigBuilder) {
            createAndFillBeanBuilder(node, SymmetricEncryptionConfig.class, "symmetricEncryptionConfig", networkConfigBuilder);
        }

        public void handleExecutor(Node node) {
            createAndFillListedBean(node, ExecutorConfig.class, "executorConfig", executorManagedMap);
        }
        
        public void handleMulticast(Node node, BeanDefinitionBuilder joinConfigBuilder) {
            createAndFillBeanBuilder(node, MulticastConfig.class, "multicastConfig", joinConfigBuilder);
        }

        public void handleTcpIp(Node node, BeanDefinitionBuilder joinConfigBuilder) {
            final BeanDefinitionBuilder builder = 
                createAndFillBeanBuilder(node, TcpIpConfig.class,
                    "tcpIpConfig",
                    joinConfigBuilder, 
                    "interface", "member", "members");
            
            final ManagedList members = new ManagedList();
            for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes(), Node.ELEMENT_NODE)) {
                String name = xmlToJavaName(cleanNodeName(n.getNodeName()));
                if ("member".equals(name) || "members".equals(name) || "interface".equals(name)) {
                    String value = getValue(n);
                    members.add(value);
                }
            }
            builder.addPropertyValue("members", members);
        }

        public void handleAws(Node node, BeanDefinitionBuilder joinConfigBuilder) {
            createAndFillBeanBuilder(node, AwsConfig.class, "awsConfig", joinConfigBuilder);
        }

        public void handleQueue(Node node) {
            createAndFillListedBean(node, QueueConfig.class, "queueConfig", queueManagedMap);
        }

        public void handleMap(Node node) {
            BeanDefinitionBuilder mapConfigBuilder = createBeanBuilder(MapConfig.class, "mapConfig");
            final AbstractBeanDefinition beanDefinition = mapConfigBuilder.getBeanDefinition();
            
            final Node attName = node.getAttributes().getNamedItem("name");
            final String name = getValue(attName);
            mapConfigBuilder.addPropertyValue("name", name);

            fillValues(node, mapConfigBuilder, "mapStore", "nearCache");
            for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes(), Node.ELEMENT_NODE)) {
                final String nname = cleanNodeName(n.getNodeName());
                if ("map-store".equals(nname)){
                    handleMapStoreConfig(n, mapConfigBuilder);
                } else if ("near-cache".equals(nname)){
                    handleNearCacheConfig(n, mapConfigBuilder);
                } else if ("wan-replication-ref".equals(nname)) {
                	final BeanDefinitionBuilder wanReplicationRefBuilder = createBeanBuilder(WanReplicationRef.class, "wanReplicationRef");
                    final AbstractBeanDefinition wanReplicationRefBeanDefinition = wanReplicationRefBuilder.getBeanDefinition();
                    fillValues(n, wanReplicationRefBuilder);
                    mapConfigBuilder.addPropertyValue("wanReplicationRef", wanReplicationRefBeanDefinition);
                }
            }
            mapConfigManagedMap.put(name, beanDefinition);
            mapConfigBuilder = null;
        }
        
        public void handleWanReplication(Node node) {
        	final BeanDefinitionBuilder wanRepConfigBuilder = createBeanBuilder(WanReplicationConfig.class, "wanReplicationConfig");
        	final AbstractBeanDefinition beanDefinition = wanRepConfigBuilder.getBeanDefinition();
        	
        	final Node attName = node.getAttributes().getNamedItem("name");
            final String name = getValue(attName);
            wanRepConfigBuilder.addPropertyValue("name", name);
            
            final ManagedList targetClusters = new ManagedList();
            for (Node n : new IterableNodeList(node.getChildNodes(), Node.ELEMENT_NODE)) {
                final String nName = cleanNodeName(n);
                if ("target-cluster".equals(nName)) {
                	final BeanDefinitionBuilder targetClusterConfigBuilder = createBeanBuilder(WanTargetClusterConfig.class, "targetClusterConfig");
                	final AbstractBeanDefinition childBeanDefinition = targetClusterConfigBuilder.getBeanDefinition();
                	fillAttributeValues(n, targetClusterConfigBuilder, Collections.EMPTY_LIST);
                	
                	for (Node childNode : new IterableNodeList(n.getChildNodes(), Node.ELEMENT_NODE)) {
                		final String childNodeName = cleanNodeName(childNode);
                		if("replication-impl".equals(childNodeName)) {
                			targetClusterConfigBuilder.addPropertyValue(xmlToJavaName(childNodeName), getValue(childNode));
                		}
                		else if("replication-impl-object".equals(childNodeName)) {
                			Node refName = childNode.getAttributes().getNamedItem("ref");
                			targetClusterConfigBuilder.addPropertyReference(xmlToJavaName(childNodeName), getValue(refName));
                		}
                		else if("end-points".equals(childNodeName)) {
                			final ManagedList addresses = new ManagedList();
                			for (Node addressNode : new IterableNodeList(childNode.getChildNodes(), Node.ELEMENT_NODE)) {
                				if("address".equals(cleanNodeName(addressNode))) {
                					addresses.add(getValue(addressNode));
                				}
                			}
                			targetClusterConfigBuilder.addPropertyValue("endpoints", addresses);
                		}
                	}

                	targetClusters.add(childBeanDefinition);
                } 
            }
            
            wanRepConfigBuilder.addPropertyValue("targetClusterConfigs", targetClusters);
            wanReplicationManagedMap.put(name, beanDefinition);
        }
        
        public void handleNearCacheConfig(Node node, BeanDefinitionBuilder mapConfigBuilder) {
            BeanDefinitionBuilder nearCacheConfigBuilder = createBeanBuilder(NearCacheConfig.class, "nearCacheConfig");
            final AbstractBeanDefinition beanDefinition = nearCacheConfigBuilder.getBeanDefinition();

            fillValues(node, nearCacheConfigBuilder);

            mapConfigBuilder.addPropertyValue("nearCacheConfig", beanDefinition);
            nearCacheConfigBuilder = null;
        }
        
        public void handleMapStoreConfig(Node node, BeanDefinitionBuilder mapConfigBuilder) {
            BeanDefinitionBuilder mapStoreConfigBuilder = createBeanBuilder(MapStoreConfig.class, "mapStoreConfig");
            final AbstractBeanDefinition beanDefinition = mapStoreConfigBuilder.getBeanDefinition();
            final String implAttrName = "implementation";
            final String factoryImplAttrName = "factory-implementation";
            
            fillValues(node, mapStoreConfigBuilder, implAttrName, factoryImplAttrName);
            handleProperties(node, mapStoreConfigBuilder);
            
            final NamedNodeMap attrs = node.getAttributes();
            final Node implRef = attrs.getNamedItem(implAttrName);
            final Node factoryImplRef = attrs.getNamedItem(factoryImplAttrName); 
            
            if(factoryImplRef != null) {
            	mapStoreConfigBuilder.addPropertyReference(xmlToJavaName(factoryImplAttrName), getValue(factoryImplRef));
            }
            if(implRef != null) {
            	mapStoreConfigBuilder.addPropertyReference(xmlToJavaName(implAttrName), getValue(implRef));
            }

            mapConfigBuilder.addPropertyValue("mapStoreConfig", beanDefinition);
            mapStoreConfigBuilder = null;
        }
        
        public void handleProperties(final org.w3c.dom.Node node, BeanDefinitionBuilder beanDefinitionBuilder) {
            ManagedMap properties = new ManagedMap();
            for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes(), Node.ELEMENT_NODE)) {
                final String name = cleanNodeName(n.getNodeName());
                final String propertyName;
                if (!"property".equals(name)){
                    continue;
                }
                propertyName = getTextContent(n.getAttributes().getNamedItem("name")).trim();
                final String value = getValue(n);
                properties.put(propertyName, value);
            }
            beanDefinitionBuilder.addPropertyValue("properties", properties);
        }

        public void handleTopic(Node node) {
            createAndFillListedBean(node, TopicConfig.class, "topicConfig", topicManagedMap);
        }

        public void handleMergePolicies(Node node) {
        	final String implAttr = "implementation";
            for (org.w3c.dom.Node n : new IterableNodeList(node.getChildNodes(), Node.ELEMENT_NODE)) {
            	if("map-merge-policy".equals(cleanNodeName(n))) {
            		BeanDefinitionBuilder mergePolicyConfigBuilder = createBeanBuilder(MergePolicyConfig.class, "mergePolicyConfig");
            		final AbstractBeanDefinition beanDefinition = mergePolicyConfigBuilder.getBeanDefinition();
            		fillValues(n, mergePolicyConfigBuilder, implAttr);
            		final Node impl = n.getAttributes().getNamedItem(implAttr);
            		if(implAttr != null) {
            			mergePolicyConfigBuilder.addPropertyReference(implAttr, getValue(impl));
            		}
            		final String name = getValue(n.getAttributes().getNamedItem("name"));
            		mergePolicyConfigMap.put(name, beanDefinition);
            	}
            }
        }
    }

}
