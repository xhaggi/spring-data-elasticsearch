/*
 * Copyright 2014-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.elasticsearch.core.index;

import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.springframework.data.elasticsearch.core.index.MappingParameters.*;
import static org.springframework.util.StringUtils.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Iterator;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Transient;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.ResourceUtil;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentEntity;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.util.TypeInformation;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Rizwan Idrees
 * @author Mohsin Husen
 * @author Artur Konczak
 * @author Kevin Leturc
 * @author Alexander Volz
 * @author Dennis Maaß
 * @author Pavel Luhin
 * @author Mark Paluch
 * @author Sascha Woo
 * @author Nordine Bittich
 * @author Robert Gruendler
 * @author Petr Kukral
 * @author Peter-Josef Meisch
 * @author Xiao Yu
 * @author Subhobrata Dey
 */
public class MappingBuilder {

	private static final Logger logger = LoggerFactory.getLogger(ElasticsearchRestTemplate.class);

	private static final String FIELD_INDEX = "index";
	private static final String FIELD_PROPERTIES = "properties";
	@Deprecated private static final String FIELD_PARENT = "_parent";
	private static final String FIELD_CONTEXT_NAME = "name";
	private static final String FIELD_CONTEXT_TYPE = "type";
	private static final String FIELD_CONTEXT_PATH = "path";
	private static final String FIELD_CONTEXT_PRECISION = "precision";
	private static final String FIELD_DYNAMIC_TEMPLATES = "dynamic_templates";

	private static final String COMPLETION_PRESERVE_SEPARATORS = "preserve_separators";
	private static final String COMPLETION_PRESERVE_POSITION_INCREMENTS = "preserve_position_increments";
	private static final String COMPLETION_MAX_INPUT_LENGTH = "max_input_length";
	private static final String COMPLETION_CONTEXTS = "contexts";

	private static final String TYPEHINT_PROPERTY = "_class";

	private static final String TYPE_DYNAMIC = "dynamic";
	private static final String TYPE_VALUE_KEYWORD = "keyword";
	private static final String TYPE_VALUE_GEO_POINT = "geo_point";
	private static final String TYPE_VALUE_JOIN = "join";
	private static final String TYPE_VALUE_COMPLETION = "completion";

	private static final String JOIN_TYPE_RELATIONS = "relations";

	private static final String MAPPING_ENABLED = "enabled";
	private static final String DATE_DETECTION = "date_detection";
	private static final String NUMERIC_DETECTION = "numeric_detection";
	private static final String DYNAMIC_DATE_FORMATS = "dynamic_date_formats";
	private static final String RUNTIME = "runtime";

	protected final ElasticsearchConverter elasticsearchConverter;

	private boolean writeTypeHints = true;

	public MappingBuilder(ElasticsearchConverter elasticsearchConverter) {
		this.elasticsearchConverter = elasticsearchConverter;
	}

	/**
	 * builds the Elasticsearch mapping for the given clazz.
	 *
	 * @return JSON string
	 * @throws MappingException on errors while building the mapping
	 */
	public String buildPropertyMapping(Class<?> clazz) throws MappingException {

		ElasticsearchPersistentEntity<?> entity = elasticsearchConverter.getMappingContext()
				.getRequiredPersistentEntity(clazz);

		return buildPropertyMapping(entity, getRuntimeFields(entity));
	}

	protected String buildPropertyMapping(ElasticsearchPersistentEntity<?> entity,
			@Nullable org.springframework.data.elasticsearch.core.document.Document runtimeFields) {

		try {

			writeTypeHints = entity.writeTypeHints();

			XContentBuilder builder = jsonBuilder().startObject();

			// Dynamic templates
			addDynamicTemplatesMapping(builder, entity);

			mapEntity(builder, entity, true, "", false, FieldType.Auto, null, entity.findAnnotation(DynamicMapping.class),
					runtimeFields);

			builder.endObject() // root object
					.close();

			return builder.getOutputStream().toString();
		} catch (IOException e) {
			throw new MappingException("could not build mapping", e);
		}
	}

	private void writeTypeHintMapping(XContentBuilder builder) throws IOException {

		if (writeTypeHints) {
			builder.startObject(TYPEHINT_PROPERTY) //
					.field(FIELD_PARAM_TYPE, TYPE_VALUE_KEYWORD) //
					.field(FIELD_PARAM_INDEX, false) //
					.field(FIELD_PARAM_DOC_VALUES, false) //
					.endObject();
		}
	}

	private void mapEntity(XContentBuilder builder, @Nullable ElasticsearchPersistentEntity<?> entity,
			boolean isRootObject, String nestedObjectFieldName, boolean nestedOrObjectField, FieldType fieldType,
			@Nullable Field parentFieldAnnotation, @Nullable DynamicMapping dynamicMapping,
			@Nullable org.springframework.data.elasticsearch.core.document.Document runtimeFields) throws IOException {

		if (entity != null && entity.isAnnotationPresent(Mapping.class)) {
			Mapping mappingAnnotation = entity.getRequiredAnnotation(Mapping.class);

			if (!mappingAnnotation.enabled()) {
				builder.field(MAPPING_ENABLED, false);
				return;
			}

			if (mappingAnnotation.dateDetection() != Mapping.Detection.DEFAULT) {
				builder.field(DATE_DETECTION, Boolean.parseBoolean(mappingAnnotation.dateDetection().name()));
			}

			if (mappingAnnotation.numericDetection() != Mapping.Detection.DEFAULT) {
				builder.field(NUMERIC_DETECTION, Boolean.parseBoolean(mappingAnnotation.numericDetection().name()));
			}

			if (mappingAnnotation.dynamicDateFormats().length > 0) {
				builder.field(DYNAMIC_DATE_FORMATS, mappingAnnotation.dynamicDateFormats());
			}

			if (runtimeFields != null) {
				builder.field(RUNTIME, runtimeFields);
			}
		}

		boolean writeNestedProperties = !isRootObject && (isAnyPropertyAnnotatedWithField(entity) || nestedOrObjectField);
		if (writeNestedProperties) {

			String type = nestedOrObjectField ? fieldType.toString().toLowerCase()
					: FieldType.Object.toString().toLowerCase();
			builder.startObject(nestedObjectFieldName).field(FIELD_PARAM_TYPE, type);

			if (nestedOrObjectField && FieldType.Nested == fieldType && parentFieldAnnotation != null
					&& parentFieldAnnotation.includeInParent()) {
				builder.field("include_in_parent", true);
			}
		}

		if (isRootObject && entity != null && entity.dynamic() != Dynamic.INHERIT) {
			builder.field(TYPE_DYNAMIC, entity.dynamic().name().toLowerCase());
		} else if (nestedOrObjectField && parentFieldAnnotation != null
				&& parentFieldAnnotation.dynamic() != Dynamic.INHERIT) {
			builder.field(TYPE_DYNAMIC, parentFieldAnnotation.dynamic().name().toLowerCase());
		} else if (dynamicMapping != null) {
			builder.field(TYPE_DYNAMIC, dynamicMapping.value().name().toLowerCase());
		}

		builder.startObject(FIELD_PROPERTIES);

		writeTypeHintMapping(builder);

		if (entity != null) {
			entity.doWithProperties((PropertyHandler<ElasticsearchPersistentProperty>) property -> {
				try {
					if (property.isAnnotationPresent(Transient.class) || isInIgnoreFields(property, parentFieldAnnotation)) {
						return;
					}

					if (property.isSeqNoPrimaryTermProperty()) {
						if (property.isAnnotationPresent(Field.class)) {
							logger.warn("Property {} of {} is annotated for inclusion in mapping, but its type is " + //
							"SeqNoPrimaryTerm that is never mapped, so it is skipped", //
									property.getFieldName(), entity.getType());
						}
						return;
					}

					buildPropertyMapping(builder, isRootObject, property);
				} catch (IOException e) {
					logger.warn("error mapping property with name {}", property.getName(), e);
				}
			});
		}

		builder.endObject();

		if (writeNestedProperties) {
			builder.endObject();
		}

	}

	@Nullable
	private org.springframework.data.elasticsearch.core.document.Document getRuntimeFields(
			@Nullable ElasticsearchPersistentEntity<?> entity) {

		if (entity != null) {
			Mapping mappingAnnotation = entity.findAnnotation(Mapping.class);
			if (mappingAnnotation != null) {
				String runtimeFieldsPath = mappingAnnotation.runtimeFieldsPath();

				if (hasText(runtimeFieldsPath)) {
					String jsonString = ResourceUtil.readFileFromClasspath(runtimeFieldsPath);
					return org.springframework.data.elasticsearch.core.document.Document.parse(jsonString);
				}
			}
		}
		return null;
	}

	private void buildPropertyMapping(XContentBuilder builder, boolean isRootObject,
			ElasticsearchPersistentProperty property) throws IOException {

		if (property.isAnnotationPresent(Mapping.class)) {

			Mapping mapping = property.getRequiredAnnotation(Mapping.class);

			if (mapping.enabled()) {
				String mappingPath = mapping.mappingPath();

				if (StringUtils.hasText(mappingPath)) {

					ClassPathResource mappings = new ClassPathResource(mappingPath);
					if (mappings.exists()) {
						builder.rawField(property.getFieldName(), mappings.getInputStream(), XContentType.JSON);
						return;
					}
				}
			} else {
				applyDisabledPropertyMapping(builder, property);
				return;
			}
		}

		if (property.isGeoPointProperty()) {
			applyGeoPointFieldMapping(builder, property);
			return;
		}

		if (property.isGeoShapeProperty()) {
			applyGeoShapeMapping(builder, property);
		}

		if (property.isJoinFieldProperty()) {
			addJoinFieldMapping(builder, property);
		}

		Field fieldAnnotation = property.findAnnotation(Field.class);
		boolean isCompletionProperty = property.isCompletionProperty();
		boolean isNestedOrObjectProperty = isNestedOrObjectProperty(property);
		DynamicMapping dynamicMapping = property.findAnnotation(DynamicMapping.class);

		if (!isCompletionProperty && property.isEntity() && hasRelevantAnnotation(property)) {

			if (fieldAnnotation == null) {
				return;
			}

			if (isNestedOrObjectProperty) {
				Iterator<? extends TypeInformation<?>> iterator = property.getPersistentEntityTypes().iterator();
				ElasticsearchPersistentEntity<?> persistentEntity = iterator.hasNext()
						? elasticsearchConverter.getMappingContext().getPersistentEntity(iterator.next())
						: null;

				mapEntity(builder, persistentEntity, false, property.getFieldName(), true, fieldAnnotation.type(),
						fieldAnnotation, dynamicMapping, null);
				return;
			}
		}

		MultiField multiField = property.findAnnotation(MultiField.class);

		if (isCompletionProperty) {
			CompletionField completionField = property.findAnnotation(CompletionField.class);
			applyCompletionFieldMapping(builder, property, completionField);
		}

		if (isRootObject && fieldAnnotation != null && property.isIdProperty()) {
			applyDefaultIdFieldMapping(builder, property);
		} else if (multiField != null) {
			addMultiFieldMapping(builder, property, multiField, isNestedOrObjectProperty, dynamicMapping);
		} else if (fieldAnnotation != null) {
			addSingleFieldMapping(builder, property, fieldAnnotation, isNestedOrObjectProperty, dynamicMapping);
		}
	}

	private boolean hasRelevantAnnotation(ElasticsearchPersistentProperty property) {

		return property.findAnnotation(Field.class) != null || property.findAnnotation(MultiField.class) != null
				|| property.findAnnotation(GeoPointField.class) != null
				|| property.findAnnotation(CompletionField.class) != null;
	}

	private void applyGeoPointFieldMapping(XContentBuilder builder, ElasticsearchPersistentProperty property)
			throws IOException {
		builder.startObject(property.getFieldName()).field(FIELD_PARAM_TYPE, TYPE_VALUE_GEO_POINT).endObject();
	}

	private void applyGeoShapeMapping(XContentBuilder builder, ElasticsearchPersistentProperty property)
			throws IOException {

		builder.startObject(property.getFieldName());
		GeoShapeMappingParameters.from(property.findAnnotation(GeoShapeField.class)).writeTypeAndParametersTo(builder);
		builder.endObject();
	}

	private void applyCompletionFieldMapping(XContentBuilder builder, ElasticsearchPersistentProperty property,
			@Nullable CompletionField annotation) throws IOException {

		builder.startObject(property.getFieldName());
		builder.field(FIELD_PARAM_TYPE, TYPE_VALUE_COMPLETION);

		if (annotation != null) {

			builder.field(COMPLETION_MAX_INPUT_LENGTH, annotation.maxInputLength());
			builder.field(COMPLETION_PRESERVE_POSITION_INCREMENTS, annotation.preservePositionIncrements());
			builder.field(COMPLETION_PRESERVE_SEPARATORS, annotation.preserveSeparators());
			if (!StringUtils.isEmpty(annotation.searchAnalyzer())) {
				builder.field(FIELD_PARAM_SEARCH_ANALYZER, annotation.searchAnalyzer());
			}
			if (!StringUtils.isEmpty(annotation.analyzer())) {
				builder.field(FIELD_PARAM_INDEX_ANALYZER, annotation.analyzer());
			}

			if (annotation.contexts().length > 0) {

				builder.startArray(COMPLETION_CONTEXTS);
				for (CompletionContext context : annotation.contexts()) {

					builder.startObject();
					builder.field(FIELD_CONTEXT_NAME, context.name());
					builder.field(FIELD_CONTEXT_TYPE, context.type().name().toLowerCase());

					if (context.precision().length() > 0) {
						builder.field(FIELD_CONTEXT_PRECISION, context.precision());
					}

					if (StringUtils.hasText(context.path())) {
						builder.field(FIELD_CONTEXT_PATH, context.path());
					}

					builder.endObject();
				}
				builder.endArray();
			}

		}
		builder.endObject();
	}

	private void applyDefaultIdFieldMapping(XContentBuilder builder, ElasticsearchPersistentProperty property)
			throws IOException {
		builder.startObject(property.getFieldName()) //
				.field(FIELD_PARAM_TYPE, TYPE_VALUE_KEYWORD) //
				.field(FIELD_INDEX, true) //
				.endObject(); //
	}

	private void applyDisabledPropertyMapping(XContentBuilder builder, ElasticsearchPersistentProperty property)
			throws IOException {

		try {
			Field field = property.getRequiredAnnotation(Field.class);

			if (field.type() != FieldType.Object) {
				throw new IllegalArgumentException("Field type must be 'object");
			}

			builder.startObject(property.getFieldName()) //
					.field(FIELD_PARAM_TYPE, field.type().name().toLowerCase()) //
					.field(MAPPING_ENABLED, false) //
					.endObject(); //
		} catch (Exception e) {
			throw new MappingException("Could not write enabled: false mapping for " + property.getFieldName(), e);
		}
	}

	/**
	 * Add mapping for @Field annotation
	 *
	 * @throws IOException
	 */
	private void addSingleFieldMapping(XContentBuilder builder, ElasticsearchPersistentProperty property,
			Field annotation, boolean nestedOrObjectField, @Nullable DynamicMapping dynamicMapping) throws IOException {

		// build the property json, if empty skip it as this is no valid mapping
		XContentBuilder propertyBuilder = jsonBuilder().startObject();
		addFieldMappingParameters(propertyBuilder, annotation, nestedOrObjectField);
		propertyBuilder.endObject().close();

		if ("{}".equals(propertyBuilder.getOutputStream().toString())) {
			return;
		}

		builder.startObject(property.getFieldName());

		if (nestedOrObjectField) {
			if (annotation.dynamic() != Dynamic.INHERIT) {
				builder.field(TYPE_DYNAMIC, annotation.dynamic().name().toLowerCase());
			} else if (dynamicMapping != null) {
				builder.field(TYPE_DYNAMIC, dynamicMapping.value().name().toLowerCase());
			}
		}

		addFieldMappingParameters(builder, annotation, nestedOrObjectField);
		builder.endObject();
	}

	private void addJoinFieldMapping(XContentBuilder builder, ElasticsearchPersistentProperty property)
			throws IOException {
		JoinTypeRelation[] joinTypeRelations = property.getRequiredAnnotation(JoinTypeRelations.class).relations();

		if (joinTypeRelations.length == 0) {
			logger.warn("Property {}s type is JoinField but its annotation JoinTypeRelation is " + //
					"not properly maintained", //
					property.getFieldName());
			return;
		}
		builder.startObject(property.getFieldName());

		builder.field(FIELD_PARAM_TYPE, TYPE_VALUE_JOIN);

		builder.startObject(JOIN_TYPE_RELATIONS);

		for (JoinTypeRelation joinTypeRelation : joinTypeRelations) {
			String parent = joinTypeRelation.parent();
			String[] children = joinTypeRelation.children();

			if (children.length > 1) {
				builder.array(parent, children);
			} else if (children.length == 1) {
				builder.field(parent, children[0]);
			}
		}
		builder.endObject();
		builder.endObject();
	}

	/**
	 * Add mapping for @MultiField annotation
	 *
	 * @throws IOException
	 */
	private void addMultiFieldMapping(XContentBuilder builder, ElasticsearchPersistentProperty property,
			MultiField annotation, boolean nestedOrObjectField, @Nullable DynamicMapping dynamicMapping) throws IOException {

		// main field
		builder.startObject(property.getFieldName());

		if (nestedOrObjectField) {
			if (annotation.mainField().dynamic() != Dynamic.INHERIT) {
				builder.field(TYPE_DYNAMIC, annotation.mainField().dynamic().name().toLowerCase());
			} else if (dynamicMapping != null) {
				builder.field(TYPE_DYNAMIC, dynamicMapping.value().name().toLowerCase());
			}
		}

		addFieldMappingParameters(builder, annotation.mainField(), nestedOrObjectField);

		// inner fields
		builder.startObject("fields");
		for (InnerField innerField : annotation.otherFields()) {
			builder.startObject(innerField.suffix());
			addFieldMappingParameters(builder, innerField, false);
			builder.endObject();
		}
		builder.endObject();

		builder.endObject();
	}

	private void addFieldMappingParameters(XContentBuilder builder, Annotation annotation, boolean nestedOrObjectField)
			throws IOException {

		MappingParameters mappingParameters = MappingParameters.from(annotation);

		if (!nestedOrObjectField && mappingParameters.isStore()) {
			builder.field(FIELD_PARAM_STORE, true);
		}
		mappingParameters.writeTypeAndParametersTo(builder);
	}

	/**
	 * Apply mapping for dynamic templates.
	 *
	 * @throws IOException
	 */
	private void addDynamicTemplatesMapping(XContentBuilder builder, ElasticsearchPersistentEntity<?> entity)
			throws IOException {

		if (entity.isAnnotationPresent(DynamicTemplates.class)) {
			String mappingPath = entity.getRequiredAnnotation(DynamicTemplates.class).mappingPath();
			if (hasText(mappingPath)) {

				String jsonString = ResourceUtil.readFileFromClasspath(mappingPath);
				if (hasText(jsonString)) {

					ObjectMapper objectMapper = new ObjectMapper();
					JsonNode jsonNode = objectMapper.readTree(jsonString).get("dynamic_templates");
					if (jsonNode != null && jsonNode.isArray()) {
						String json = objectMapper.writeValueAsString(jsonNode);
						builder.rawField(FIELD_DYNAMIC_TEMPLATES, new ByteArrayInputStream(json.getBytes()), XContentType.JSON);
					}
				}
			}
		}
	}

	private boolean isAnyPropertyAnnotatedWithField(@Nullable ElasticsearchPersistentEntity entity) {

		return entity != null && entity.getPersistentProperty(Field.class) != null;
	}

	private boolean isInIgnoreFields(ElasticsearchPersistentProperty property, @Nullable Field parentFieldAnnotation) {

		if (null != parentFieldAnnotation) {

			String[] ignoreFields = parentFieldAnnotation.ignoreFields();
			return Arrays.asList(ignoreFields).contains(property.getFieldName());
		}
		return false;
	}

	private boolean isNestedOrObjectProperty(ElasticsearchPersistentProperty property) {

		Field fieldAnnotation = property.findAnnotation(Field.class);
		return fieldAnnotation != null
				&& (FieldType.Nested == fieldAnnotation.type() || FieldType.Object == fieldAnnotation.type());
	}
}
