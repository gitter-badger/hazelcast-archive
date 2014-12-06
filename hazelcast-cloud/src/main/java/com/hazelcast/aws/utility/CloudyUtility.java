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

package com.hazelcast.aws.utility;

import com.hazelcast.config.AbstractXmlConfigHelper;
import com.hazelcast.impl.Util;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static com.hazelcast.config.AbstractXmlConfigHelper.cleanNodeName;

public class CloudyUtility {
    final static ILogger logger = Logger.getLogger(CloudyUtility.class.getName());

    public static String getQueryString(Map<String, String> attributes) {
        StringBuilder query = new StringBuilder();
        for (Iterator<String> iterator = attributes.keySet().iterator(); iterator.hasNext();) {
            String key = iterator.next();
            String value = attributes.get(key);
            query.append(AwsURLEncoder.urlEncode(key)).append("=").append(AwsURLEncoder.urlEncode(value)).append("&");
        }
        String result = query.toString();
        if (result != null && !result.equals(""))
            result = "?" + result.substring(0, result.length() - 1);
        return result;
    }

    public static Object unmarshalTheResponse(InputStream stream) throws IOException {
        Object o = parse(stream);
        return o;
    }

    private static Object parse(InputStream in) {
        final DocumentBuilder builder;
        try {
            builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(in);
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Util.streamXML(doc, baos);
            final byte[] bytes = baos.toByteArray();
            final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            Element element = doc.getDocumentElement();
            NodeHolder elementNodeHolder = new NodeHolder(element);
            List<String> names = new ArrayList<String>();
            List<NodeHolder> reservationset = elementNodeHolder.getSubNodes("reservationset");
            for (NodeHolder reservation : reservationset) {
                List<NodeHolder> items = reservation.getSubNodes("item");
                for (NodeHolder item : items) {
                    NodeHolder instancesset = item.getSub("instancesset");
                    names.addAll(instancesset.getList("privateipaddress"));
                }
            }
            return names;
        } catch (Exception e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
        return new ArrayList<String>();
    }

    static class NodeHolder {
        Node node;

        public NodeHolder(Node node) {
            this.node = node;
        }

        public NodeHolder getSub(String name) {
            if (node != null) {
                for (org.w3c.dom.Node node : new AbstractXmlConfigHelper.IterableNodeList(this.node.getChildNodes())) {
                    String nodeName = cleanNodeName(node.getNodeName());
                    if (name.equals(nodeName)) {
                        return new NodeHolder(node);
                    }
                }
            }
            return new NodeHolder(null);
        }

        public List<NodeHolder> getSubNodes(String name) {
            List<NodeHolder> list = new ArrayList<NodeHolder>();
            if (node != null) {
                for (org.w3c.dom.Node node : new AbstractXmlConfigHelper.IterableNodeList(this.node.getChildNodes())) {
                    String nodeName = cleanNodeName(node.getNodeName());
                    if (name.equals(nodeName)) {
                        list.add(new NodeHolder(node));
                    }
                }
            }
            return list;
        }

        public List<String> getList(String name) {
            List<String> list = new ArrayList<String>();
            if (node != null) {
                for (org.w3c.dom.Node node : new AbstractXmlConfigHelper.IterableNodeList(this.node.getChildNodes())) {
                    String nodeName = cleanNodeName(node.getNodeName());
                    if ("item".equals(nodeName)) {
                        if (new NodeHolder(node).getSub("instancestate").getSub("name").getNode().getFirstChild().getNodeValue().equals("running")) {
                            String ip = new NodeHolder(node).getSub(name).getNode().getFirstChild().getNodeValue();
                            if (ip != null) {
                                list.add(ip);
                            }
                        }
                    }
                }
            }
            return list;
        }

        public Node getNode() {
            return node;
        }
    }
}
