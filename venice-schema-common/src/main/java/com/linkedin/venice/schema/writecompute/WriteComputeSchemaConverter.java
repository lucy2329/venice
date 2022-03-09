package com.linkedin.venice.schema.writecompute;

import com.linkedin.avroutil1.compatibility.AvroCompatibilityHelper;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.schema.AvroSchemaParseUtils;
import com.linkedin.venice.schema.SchemaUtils;
import io.tehuti.utils.Utils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.commons.lang.Validate;

import static com.linkedin.venice.schema.writecompute.WriteComputeConstants.*;
import static com.linkedin.venice.schema.writecompute.WriteComputeOperation.*;
import static org.apache.avro.Schema.*;
import static org.apache.avro.Schema.Type.*;


/**
 * This class converts an arbitrary Avro schema to its write compute schema.
 *
 * Currently, it supports record partial update, collection merging and deletion
 * See {@link WriteComputeOperation} for details.
 *
 * N.B.
 * 1. We should keep {@link WriteComputeOperation} backward compatible. That's being said, if you change it, make
 * sure to release SN earlier than Samza/H2V plugin.
 *
 * 2. We should ask partners to assign a default value or wrap the field with nullable union if they intent to use
 * "partial put" to create new k/v pair (when the key is not existing in the store).
 *
 * 3. nested parsing is not allowed in order to reduce the complexity. Only the top level fields will be converted.
 * Nested Record/Array/Map will remain the same after parsing.
 */
public class WriteComputeSchemaConverter {
  private final static WriteComputeSchemaConverter instance = new WriteComputeSchemaConverter();

  private WriteComputeSchemaConverter() {}

  public static Schema convertFromValueRecordSchema(String valueRecordSchemaStr) {
    Validate.notNull(valueRecordSchemaStr);
    return convertFromValueRecordSchema(AvroSchemaParseUtils.parseSchemaFromJSONStrictValidation(valueRecordSchemaStr));
  }

  /**
   * This method is the only entry point!
   *
   * Convert schema of a store value to its write-compute schema. Note that the value record schema must be of a Record
   * type because write compute only works with Avro Record and each field needs to have a default value.
   *
   * @param valueRecordSchema schema of store value.
   * @return Generated write-compute schema.
   */
  public static Schema convertFromValueRecordSchema(Schema valueRecordSchema) {
    Validate.notNull(valueRecordSchema);
    if (valueRecordSchema.getType() != RECORD) {
      throw new IllegalStateException("Cannot generate write-compute schema from non-Record value schema. Got value "
          + "schema: " + valueRecordSchema);
    }
    return convert(valueRecordSchema);
  }

  // Visible for testing
  @Deprecated // TODO: make this method private. It is public for now to only let existing tests work. Write compute
              // schema should NOT be generated by using this method directly!
  public static Schema convert(String schemaStr) {
    return convert(AvroCompatibilityHelper.parse(schemaStr));
  }

  // Visible for testing
  @Deprecated // TODO: make this method private. It is public for now to only let existing tests work. Write compute
              // schema should NOT be generated by using this method directly!
  public static Schema convert(Schema schema) {
    String name = null;

    /**
     * If this is a record, we'd like to append a suffix to the name so that the write
     * schema name wouldn't collide with the original schema name
     */
    if (schema.getType() == RECORD) {
      name = schema.getName() + WRITE_COMPUTE_RECORD_SCHEMA_SUFFIX;
    }
    Schema convertedSchema = convert(schema, name, null);
    return convertedSchema.getType() == RECORD ? instance.wrapDelOpUnion(convertedSchema) : convertedSchema;
  }

  /**
   * Parse the given schema to its corresponding write compute schema
   * @param derivedSchemaName the name of the output derived schema. This can be null and in that case, it will
   *                          inherit the same name from the original schema.
   * @param namespace This can be null and it is only set up for arrays/maps in a record. Since the write compute
   *                  operation record will be called "ListOps"/"MapOps" regardless of the element type, duplicate
   *                  definition error might occur when a Record contains multiple arrays/maps. In case it happens,
   *                  we inherit field name as the namespace to distinguish them.
   */
  private static Schema convert(Schema originSchema, String derivedSchemaName, String namespace) {
    switch (originSchema.getType()) {
      case RECORD:
        return instance.convertRecord(originSchema, derivedSchemaName);
      case ARRAY:
        return instance.convertArray(originSchema, derivedSchemaName, namespace);
      case MAP:
        return instance.convertMap(originSchema, derivedSchemaName, namespace);
      case UNION:
        return instance.convertUnion(originSchema, derivedSchemaName, namespace);
      default:
        return originSchema;
    }
  }

  /**
   * Wrap a record schema with possible write compute operations. Recursive parsing happens for each field.
   * e.g.
   * origin record schema:
   * {
   *   "type" : "record",
   *   "name" : "testRecord",
   *   "fields" : [ {
   *     "name" : "intField",
   *     "type" : "int",
   *     "default" : 0
   *   }, {
   *     "name" : "floatArray",
   *     "type" : {
   *       "type" : "array",
   *       "items" : "float"
   *     },
   *     "default" : [ ]
   *   } ]
   * }
   *
   * write compute record schema:
   * [{
   *   "type" : "record",
   *   "name" : "testRecordWriteOpRecord",
   *   "fields" : [ {
   *     "name" : "intField",
   *     "type" : [ {
   *       "type" : "record",
   *       "name" : "NoOp",
   *       "fields" : [ ]
   *     }, "int" ],
   *     "default" : { }
   *   }, {
   *     "name" : "floatArray",
   *     "type" : [ "NoOp", {
   *       "type" : "record",
   *       "name" : "floatArrayListOps",
   *       "fields" : [ {
   *         "name" : "setUnion",
   *         "type" : {
   *           "type" : "array",
   *           "items" : "float"
   *         },
   *         "default" : [ ]
   *       }, {
   *         "name" : "setDiff",
   *         "type" : {
   *           "type" : "array",
   *           "items" : "float"
   *         },
   *         "default" : [ ]
   *       } ]
   *     }, {
   *       "type" : "array",
   *       "items" : "float"
   *     } ],
   *     "default" : { }
   *   } ]
   * }, {
   *   "type" : "record",
   *   "name" : "DelOp",
   *   "fields" : [ ]
   * } ]
   *
   * @param recordSchema the original record schema
   */
  private Schema convertRecord(Schema recordSchema, String derivedSchemaName) {
    String recordNamespace = recordSchema.getNamespace();

    if (derivedSchemaName == null) {
      derivedSchemaName = recordSchema.getName();
    }

    Schema newSchema = Schema.createRecord(derivedSchemaName, recordSchema.getDoc(), recordNamespace,
        recordSchema.isError());
    List<Field> fieldList = new ArrayList<>(recordSchema.getFields().size());
    for (Field field : recordSchema.getFields()) {
      if (!AvroCompatibilityHelper.fieldHasDefault(field)) {
        throw new VeniceException(String.format("Cannot generate derived schema because field: \"%s\" "
            + "does not have a default value.", field.name()));
      }

      final Schema.Field writeComputeField = AvroCompatibilityHelper.newField(null)
          .setName(field.name())
          .setSchema(
              wrapNoopUnion(
                  recordNamespace, field.schema().getType() == RECORD ?
                      field.schema() :
                      convert(field.schema(), field.name(), recordNamespace)
              )
          )
          .setDoc(field.doc())
          .setOrder(field.order())
          .setDefault(Collections.emptyMap())
          .build();

      // Convert each field. We'd like to skip parsing "RECORD" type in order to avoid recursive parsing
      fieldList.add(writeComputeField);
    }

    newSchema.setFields(fieldList);
    return newSchema;
  }

  /**
   * Wrap an array schema with possible write compute array operations.
   * N. B. We're not supporting nested operation such as adding elements to the inner array for an
   * array of array. Nested operations increase the complexity on both schema generation side and
   * write compute process side. We'll add the support in the future if it's needed.
   *
   * e.g.
   * origin array schema:
   * { "type": "array", "items": "int" }
   *
   * write compute array schema:
   * [ {
   *   "type" : "record",
   *   "name" : "ListOps",
   *   "fields" : [ {
   *     "name" : "setUnion",
   *     "type" : {
   *       "type" : "array",
   *       "items" : "int"
   *     },
   *     "default" : [ ]
   *   }, {
   *     "name" : "setDiff",
   *     "type" : {
   *       "type" : "array",
   *       "items" : "int"
   *     },
   *     "default" : [ ]
   *   } ]
   * }, {
   *   "type" : "array",
   *   "items" : "int"
   * } ]
   *
   * @param arraySchema the original array schema
   * @param name
   * @param namespace The namespace in "ListOps" record. See {@link #convert(Schema, String, String)} for details.
   */
  private Schema convertArray(Schema arraySchema, String name, String namespace) {
    return Schema.createUnion(Arrays.asList(createCollectionOperationSchema(LIST_OPS, arraySchema, name, namespace),
        arraySchema));
  }

  /**
   * Wrap up a map schema with possible write compute map operations.
   * e.g.
   * origin map schema:
   * { "type": "map", "values": "int"}
   *
   * write compute map schema
   * [ {
   *   "type" : "record",
   *   "name" : "MapOps",
   *   "fields" : [ {
   *     "name" : "mapUnion",
   *     "type" : {
   *       "type" : "map",
   *       "values" : "int"
   *     },
   *     "default" : { }
   *   }, {
   *     "name" : "mapDiff",
   *     "type" : {
   *       "type" : "array",
   *       "items" : "string"
   *     },
   *     "default" : [ ]
   *   } ]
   * }, {
   *   "type" : "map",
   *   "values" : "int"
   * } ]
   *
   * @param mapSchema the original map schema
   * @param namespace the namespace in "MapOps" record. See {@link #convert(Schema, String, String)} for details.
   */
  private Schema convertMap(Schema mapSchema, String name, String namespace) {
    return Schema.createUnion(Arrays.asList(createCollectionOperationSchema(MAP_OPS, mapSchema, name, namespace),
        mapSchema));
  }

  /**
   * Wrap up a union schema with possible write compute operations.
   *
   * N.B.: if it's an top level union field, the convert will try to convert the elements(except for RECORD)
   * inside the union. It's not supported if an union contains more than 1 collections since it will
   * confuse the interpreter about which elements the operation is supposed to be applied.
   *
   * e.g.
   * original schema:
   *{
   *   "type" : "record",
   *   "name" : "ecord",
   *   "fields" : [ {
   *     "name" : "nullableArrayField",
   *     "type" : [ "null", {
   *       "type" : "array",
   *       "items" : {
   *         "type" : "record",
   *         "name" : "simpleRecord",
   *         "fields" : [ {
   *           "name" : "intField",
   *           "type" : "int",
   *           "default" : 0
   *         } ]
   *       }
   *     } ],
   *     "default" : null
   *   } ]
   * }
   *
   * write compute schema:
   * [ {
   *   "type" : "record",
   *   "name" : "testRecordWriteOpRecord",
   *   "fields" : [ {
   *     "name" : "nullableArrayField",
   *     "type" : [ {
   *       "type" : "record",
   *       "name" : "NoOp",
   *       "fields" : [ ]
   *     }, "null", {
   *       "type" : "record",
   *       "name" : "nullableArrayFieldListOps",
   *       "fields" : [ {
   *         "name" : "setUnion",
   *         "type" : {
   *           "type" : "array",
   *           "items" : {
   *             "type" : "record",
   *             "name" : "simpleRecord",
   *             "fields" : [ {
   *               "name" : "intField",
   *               "type" : "int",
   *               "default" : 0
   *             } ]
   *           }
   *         },
   *         "default" : [ ]
   *       }, {
   *         "name" : "setDiff",
   *         "type" : {
   *           "type" : "array",
   *           "items" : "simpleRecord"
   *         },
   *         "default" : [ ]
   *       } ]
   *     }, {
   *       "type" : "array",
   *       "items" : "simpleRecord"
   *     } ],
   *     "default" : { }
   *   } ]
   * }, {
   *   "type" : "record",
   *   "name" : "DelOp",
   *   "fields" : [ ]
   * } ]
   */
  private Schema convertUnion(Schema unionSchema, String name, String namespace) {
    if (unionSchema.getType() != UNION) {
      throw new VeniceException("Expect schema to be UNION type. Got: " + unionSchema);
    }
    SchemaUtils.containsOnlyOneCollection(unionSchema);
    return SchemaUtils.createFlattenedUnionSchema(unionSchema.getTypes().stream().sequential()
        .map(schema -> {
          Schema.Type type = schema.getType();
          if (type == RECORD) {
            return schema;
          }

          return convert(schema, name, namespace);
        })
        .collect(Collectors.toList()));
  }

  /**
   * Wrap up one or more schema with Noop record into a union. If the origin schema is an union,
   * it will be flattened. (instead of becoming nested unions)
   * @param schemaList
   * @return an union schema that contains all schemas in the list plus Noop record
   */
  private Schema wrapNoopUnion(String namespace, Schema... schemaList) {
    LinkedList<Schema> list = new LinkedList<>(Arrays.asList(schemaList));
    //always put NO_OP at the first place so that it will be the default value of the union
    list.addFirst(getNoOpOperation(namespace));

    return SchemaUtils.createFlattenedUnionSchema(list);
  }

  /**
   * Wrap up schema with DelOp record into a union. The origin schema
   * must be a record schema
   * @param schema
   * @return an union schema that contains all schemas in the list plus Noop record
   */
  private Schema wrapDelOpUnion(Schema schema) {
    if (schema.getType() != RECORD) {
      return schema;
    }
    return SchemaUtils.createFlattenedUnionSchema(Arrays.asList(schema, getDelOpOperation(schema.getNamespace())));
  }

  private Schema createCollectionOperationSchema(
      WriteComputeOperation collectionOperation,
      Schema collectionSchema,
      String name,
      String namespace
  ) {
    if (name == null) {
      name = collectionOperation.getName();
    } else {
      name = name + collectionOperation.getUpperCamelName();
    }

    Schema operationSchema = Schema.createRecord(name, null, namespace, false);
    operationSchema.setFields(Arrays.stream(collectionOperation.params.get())
        .map(param -> param.apply(collectionSchema))
        .collect(Collectors.toList()));
    return operationSchema;
  }

  public Schema getNoOpOperation(String namespace) {
    Schema noOpSchema = Schema.createRecord(NO_OP_ON_FIELD.getName(), null, namespace, false);

    //Avro requires every record to have a list of fields even if it's empty... Otherwise, NPE
    //will be thrown out during parsing the schema.
    noOpSchema.setFields(Collections.emptyList());
    return noOpSchema;
  }

  public Schema getDelOpOperation(String namespace) {
    Schema delOpSchema = Schema.createRecord(DEL_RECORD_OP.getName(), null, namespace, false);

    //Avro requires every record to have a list of fields even if it's empty... Otherwise, NPE
    //will be thrown out during parsing the schema.
    delOpSchema.setFields(Collections.emptyList());
    return delOpSchema;
  }

  public static WriteComputeOperation getFieldOperationType(Object writeComputeFieldValue) {
    Utils.notNull(writeComputeFieldValue);

    if (writeComputeFieldValue instanceof IndexedRecord) {
      IndexedRecord writeComputeFieldRecord = (IndexedRecord) writeComputeFieldValue;
      String writeComputeFieldSchemaName = writeComputeFieldRecord.getSchema().getName();

      if (writeComputeFieldSchemaName.equals(NO_OP_ON_FIELD.name)) {
        return NO_OP_ON_FIELD;
      }

      if (writeComputeFieldSchemaName.endsWith(LIST_OPS.name)) {
        return LIST_OPS;
      }

      if (writeComputeFieldSchemaName.endsWith(MAP_OPS.name)) {
        return MAP_OPS;
      }
    }
    return PUT_NEW_FIELD;
  }

  public static boolean isDeleteRecordOp(GenericRecord writeComputeRecord) {
    return writeComputeRecord.getSchema().getName().equals(DEL_RECORD_OP.name);
  }
}
