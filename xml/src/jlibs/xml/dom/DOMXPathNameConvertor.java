/**
 * JLibs: Common Utilities for Java
 * Copyright (C) 2009  Santhosh Kumar T
 * <p/>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package jlibs.xml.dom;

import jlibs.core.graph.Convertor;
import jlibs.core.lang.StringUtil;
import org.w3c.dom.Attr;
import org.w3c.dom.Node;

/**
 * @author Santhosh Kumar T
 */
public class DOMXPathNameConvertor implements Convertor<Node, String>{
    @Override
    public String convert(Node source){
        switch(source.getNodeType()){
            case Node.DOCUMENT_NODE:
                return "/";
            case Node.TEXT_NODE:
            case Node.CDATA_SECTION_NODE:
                return "text()";
            case Node.COMMENT_NODE:
                return "comment()";
            case Node.ELEMENT_NODE:
                String prefix = source.lookupPrefix(source.getNamespaceURI());
                String name = source.getNodeName();
                return StringUtil.isEmpty(prefix) ? name : prefix+':'+name;
            case Node.ATTRIBUTE_NODE:
                return '@'+convert(((Attr)source).getOwnerElement());
            default:
                return null;
        }
    }
}
