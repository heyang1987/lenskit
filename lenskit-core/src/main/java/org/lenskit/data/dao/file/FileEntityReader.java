/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.data.dao.file;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.ClassUtils;
import org.lenskit.data.entities.Attribute;
import org.lenskit.data.entities.BasicEntityBuilder;
import org.lenskit.data.entities.EntityDefaults;
import org.lenskit.data.entities.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class FileEntityReader {
    private static final Logger logger = LoggerFactory.getLogger(FileEntityReader.class);
    private final String name;
    private Path sourceFile;
    private EntityFormat format;

    public FileEntityReader(String name) {
        this.name = name;
    }

    /**
     * Get the name of this data source.
     * @return The data source name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set the source file for this reader.
     * @param file The source file.
     */
    public void setFile(Path file) {
        this.sourceFile = file;
    }

    /**
     * Get the source file for this data source.
     * @return The source file.
     */
    public Path getFile() {
        return sourceFile;
    }

    /**
     * Set the entity format for the reader.
     * @param format The entity format.
     */
    public void setFormat(EntityFormat format) {
        this.format = format;
    }

    /**
     * Get the entity format for the reader.
     * @return The entity format.
     */
    public EntityFormat getFormat() {
        return format;
    }

    /**
     * Create a file reader.
     * @param name The reader name.
     * @param object The configuring object.
     * @param dir The base directory.
     * @return The new entity reader.
     */
    static FileEntityReader fromJSON(String name, JsonNode object, Path dir) {
        FileEntityReader source = new FileEntityReader(name);
        source.setFile(dir.resolve(object.get("file").asText()));
        logger.info("loading text file source {} to read from {}", name, source.getFile());

        String fmt = object.path("format").asText("delimited").toLowerCase();
        String delim;
        switch (fmt) {
        case "csv":
            delim = ",";
            break;
        case "tsv":
        case "delimited":
            delim = "\t";
            break;
        default:
            throw new IllegalArgumentException("unsupported data format " + fmt);
        }
        JsonNode delimNode = object.path("delimiter");
        if (delimNode.isValueNode()) {
            delim = delimNode.asText();
        }

        DelimitedColumnEntityFormat format = new DelimitedColumnEntityFormat();
        format.setDelimiter(delim);
        logger.debug("{}: using delimiter {}", name, delim);
        JsonNode header = object.path("header");
        boolean canUseColumnMap = false;
        if (header.isBoolean() && header.asBoolean()) {
            logger.debug("{}: reading header", name);
            format.setHeader(true);
            canUseColumnMap = true;
        } else if (header.isNumber()) {
            format.setHeaderLines(header.asInt());
            logger.debug("{}: skipping {} header lines", format.getHeaderLines());
        }

        String eTypeName = object.path("entity_type").asText("rating").toLowerCase();
        EntityType etype = EntityType.forName(eTypeName);
        logger.debug("{}: reading entities of type {}", name, etype);
        EntityDefaults entityDefaults = EntityDefaults.lookup(etype);
        format.setEntityType(etype);
        format.setEntityBuilder(entityDefaults != null ? entityDefaults.getDefaultBuilder() : BasicEntityBuilder.class);

        JsonNode columns = object.path("columns");
        if (columns.isMissingNode() || columns.isNull()) {
            List<Attribute<?>> defColumns = entityDefaults != null ? entityDefaults.getDefaultColumns() : null;
            if (defColumns == null) {
                throw new IllegalArgumentException("no columns specified and no default columns available");
            }

            for (Attribute<?> attr: entityDefaults.getDefaultColumns()) {
                format.addColumn(attr);
            }
        } else if (columns.isObject()) {
            if (!canUseColumnMap) {
                throw new IllegalArgumentException("cannot use column map without file header");
            }
            Iterator<Map.Entry<String, JsonNode>> colIter = columns.fields();
            while (colIter.hasNext()) {
                Map.Entry<String, JsonNode> col = colIter.next();
                format.addColumn(col.getKey(), parseAttribute(entityDefaults, col.getValue()));
            }
        } else if (columns.isArray()) {
            for (JsonNode col: columns) {
                format.addColumn(parseAttribute(entityDefaults, col));
            }
        } else {
            throw new IllegalArgumentException("invalid format for columns");
        }

        JsonNode ebNode = object.path("builder");
        if (ebNode.isTextual()) {
            String ebName = ebNode.asText();
            if (ebName.equals("basic")) {
                format.setEntityBuilder(BasicEntityBuilder.class);
            } else {
                Class bld;
                try {
                    // FIXME Use a configurable class loader
                    bld = ClassUtils.getClass(ebName);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("cannot load class " + ebName, e);
                }
                format.setEntityBuilder(bld);
            }
        }
        logger.debug("{}: using entity builder {}", format.getEntityBuilder());

        source.setFormat(format);
        return source;
    }

    private static Attribute<?> parseAttribute(EntityDefaults entityDefaults, JsonNode col) {
        if (col.isNull() || col.isMissingNode()) {
            return null;
        } else if (col.isObject()) {
            String name = col.get("name").asText();
            String type = col.get("type").asText();
            return Attribute.create(name, type);
        } else if (col.isTextual()) {
            Attribute<?> attr = entityDefaults.getAttribute(col.asText());
            if (attr == null) {
                attr = Attribute.create(col.asText(), String.class);
            }
            return attr;
        } else {
            throw new IllegalArgumentException("invalid attribute specification: " + col.toString());
        }
    }
}