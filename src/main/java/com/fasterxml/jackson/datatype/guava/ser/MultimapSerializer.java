package com.fasterxml.jackson.datatype.guava.ser;

import java.io.IOException;
import java.util.Collection;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.ContainerSerializer;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.impl.PropertySerializerMap;
import com.fasterxml.jackson.databind.type.MapLikeType;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import java.util.HashSet;
import java.util.Map;

public class MultimapSerializer
		extends ContainerSerializer<Multimap<?,?>>
    implements ContextualSerializer
{
    private final MapLikeType type;
    private final BeanProperty property;
    private final JsonSerializer<Object> keySerializer;
    private final TypeSerializer valueTypeSerializer;
    private final JsonSerializer<Object> valueSerializer;
		/**
     * If value type can not be statically determined, mapping from
     * runtime value types to serializers are stored in this object.
     */
    protected PropertySerializerMap _dynamicValueSerializers;

    public MultimapSerializer(SerializationConfig config,
            MapLikeType type,
            BeanDescription beanDesc,
            JsonSerializer<Object> keySerializer,
            TypeSerializer valueTypeSerializer,
            JsonSerializer<Object> valueSerializer)
    {
				super(Multimap.class, false);
        this.type = type;
        this.property = null;
        this.keySerializer = keySerializer;
        this.valueTypeSerializer = valueTypeSerializer;
        this.valueSerializer = valueSerializer;
				this._dynamicValueSerializers = PropertySerializerMap.emptyMap();
    }

    @SuppressWarnings("unchecked")
    protected MultimapSerializer(MultimapSerializer src, BeanProperty property,
                JsonSerializer<?> keySerializer,
                TypeSerializer valueTypeSerializer, JsonSerializer<?> valueSerializer)
    {
				super(Multimap.class, false);
        this.type = src.type;
        this.property = property;
        this.keySerializer = (JsonSerializer<Object>) keySerializer;
        this.valueTypeSerializer = valueTypeSerializer;
        this.valueSerializer = (JsonSerializer<Object>) valueSerializer;
				this._dynamicValueSerializers = src._dynamicValueSerializers;
    }

		protected MultimapSerializer(MultimapSerializer src, TypeSerializer vts)
    {
        super(Multimap.class, false);
				this.type = src.type;
//        _valueTypeIsStatic = src._valueTypeIsStatic;
        valueTypeSerializer = vts;
        keySerializer = src.keySerializer;
        valueSerializer = src.valueSerializer;
        _dynamicValueSerializers = src._dynamicValueSerializers;
        property = src.property;
    }

    protected MultimapSerializer withResolved(BeanProperty property,
            JsonSerializer<?> keySerializer,
            TypeSerializer valueTypeSerializer, JsonSerializer<?> valueSerializer)
    {
        return new MultimapSerializer(this, property, keySerializer,
                valueTypeSerializer, valueSerializer);
    }

    /*
    /**********************************************************
    /* Post-processing (contextualization)
    /**********************************************************
     */

		@Override
    public JsonSerializer<?> createContextual(SerializerProvider provider,
            BeanProperty property) throws JsonMappingException
    {
        JsonSerializer<?> valueSer = valueSerializer;
        if (valueSer == null) { // if type is final, can actually resolve:
            JavaType valueType = type.getContentType();
            if (valueType.isFinal()) {
                valueSer = provider.findValueSerializer(valueType, property);
            }
        } else if (valueSer instanceof ContextualSerializer) {
            valueSer = ((ContextualSerializer) valueSer).createContextual(provider, property);
        }
        JsonSerializer<?> keySer = keySerializer;
        if (keySer == null) {
            keySer = provider.findKeySerializer(type.getKeyType(), property);
        } else if (keySer instanceof ContextualSerializer) {
            keySer = ((ContextualSerializer) keySer).createContextual(provider, property);
        }
        // finally, TypeSerializers may need contextualization as well
        TypeSerializer typeSer = valueTypeSerializer;
        if (typeSer != null) {
            typeSer = typeSer.forProperty(property);
        }
        return withResolved(property, keySer, typeSer, valueSer);
    }

    /*
    /**********************************************************
    /* JsonSerializer implementation
    /**********************************************************
     */

    @Override
    public void serialize(Multimap<?, ?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonProcessingException
    {
        jgen.writeStartObject();
        if (!value.isEmpty()) {
            serializeFields(value, jgen, provider);
        }
        jgen.writeEndObject();
    }

    @Override
    public void serializeWithType(Multimap<?,?> value, JsonGenerator jgen, SerializerProvider provider,
            TypeSerializer typeSer)
        throws IOException, JsonGenerationException
    {
        typeSer.writeTypePrefixForObject(value, jgen);
        serializeFields(value, jgen, provider);
        typeSer.writeTypeSuffixForObject(value, jgen);
    }

    private void serializeFields(Multimap<?, ?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonProcessingException
    {
				// If value type needs polymorphic type handling, some more work needed:
        if (valueTypeSerializer != null) {
            serializeTypedFields(value, jgen, provider);
            return;
        }
				PropertySerializerMap serializers = _dynamicValueSerializers;
        for (Entry<?, ? extends Collection<?>> e : value.asMap().entrySet()) {
						final Object keyElem = e.getKey();
            if (keySerializer != null) {
                keySerializer.serialize(keyElem, jgen, provider);
            } else {
                provider.findKeySerializer(provider.constructType(String.class), property)
                    .serialize(keyElem, jgen, provider);
            }
            if (valueSerializer != null) {
								jgen.writeStartArray();
                // note: value is a List, but generic type is for contents... so:
                for (Object vv : e.getValue()) {
                    valueSerializer.serialize(vv, jgen, provider);
                }
								jgen.writeEndArray();
            } else {
								Object valueElem = e.getValue();
								Class<?> cc = valueElem.getClass();
                JsonSerializer<Object> serializer = serializers.serializerFor(cc);
                if (serializer == null) {
                    if (type.getContentType().hasGenericTypes()) {
                        serializer = _findAndAddDynamic(serializers,
                                provider.constructSpecializedType(type.getContentType(), cc), provider);
                    } else {
                        serializer = _findAndAddDynamic(serializers, cc, provider);
                    }
                    serializers = _dynamicValueSerializers;
                }
                try {
                    serializer.serialize(valueElem, jgen, provider);
                } catch (Exception ex) {
                    // [JACKSON-55] Need to add reference information
                    String keyDesc = ""+keyElem;
                    wrapAndThrow(provider, ex, value, keyDesc);
                }
            }
        }
    }

		protected void serializeTypedFields(Multimap<?,?> value, JsonGenerator jgen, SerializerProvider provider)
        throws IOException, JsonGenerationException
    {
        JsonSerializer<Object> prevValueSerializer = null;
        Class<?> prevValueClass = null;
        final boolean skipNulls = !provider.isEnabled(SerializationFeature.WRITE_NULL_MAP_VALUES);

				for (Entry<?, ? extends Collection<?>> entry : value.asMap().entrySet()) {
            // First, serialize key
            Collection<?> valuesElem = entry.getValue();
            Object keyElem = entry.getKey();
            if (keyElem == null) {
                provider.findNullKeySerializer(type.getKeyType(), property).serialize(null, jgen, provider);
            } else {
                // [JACKSON-314] also may need to skip entries with null values
                if (skipNulls && valuesElem == null) continue;
                keySerializer.serialize(keyElem, jgen, provider);
            }

            // And then values
						jgen.writeStartArray();
						for (Object valueElem : valuesElem) {
							if (valueElem == null) {
									provider.defaultSerializeNull(jgen);
							} else {
									Class<?> cc = valueElem.getClass();
									JsonSerializer<Object> currSerializer;
									if (cc == prevValueClass) {
											currSerializer = prevValueSerializer;
									} else {
											currSerializer = provider.findValueSerializer(cc, property);
											prevValueSerializer = currSerializer;
											prevValueClass = cc;
									}
									try {
											currSerializer.serializeWithType(valueElem, jgen, provider, valueTypeSerializer);
									} catch (Exception e) {
											// [JACKSON-55] Need to add reference information
											String keyDesc = ""+keyElem;
											wrapAndThrow(provider, e, value, keyDesc);
									}
							}
						}
						jgen.writeEndArray();
        }
    }

		@Override
    public JavaType getContentType() {
        return type.getContentType();
    }

    @Override
    public JsonSerializer<?> getContentSerializer() {
        return valueSerializer;
    }

    @Override
    public boolean isEmpty(Multimap<?,?> value) {
        return (value == null) || value.isEmpty();
    }

    @Override
    public boolean hasSingleElement(Multimap<?,?> value) {
        return (value.size() == 1);
    }

		@Override
		protected MultimapSerializer _withValueTypeSerializer(TypeSerializer vts) {
			return new MultimapSerializer(this, vts);
		}

		/*
    /**********************************************************
    /* Internal helper methods
    /**********************************************************
     */

    protected final JsonSerializer<Object> _findAndAddDynamic(PropertySerializerMap map,
            Class<?> type, SerializerProvider provider) throws JsonMappingException
    {
        PropertySerializerMap.SerializerAndMapResult result = map.findAndAddSerializer(type, provider, property);
        // did we get a new map of serializers? If so, start using it
        if (map != result.map) {
            _dynamicValueSerializers = result.map;
        }
        return result.serializer;
    }

    protected final JsonSerializer<Object> _findAndAddDynamic(PropertySerializerMap map,
            JavaType type, SerializerProvider provider) throws JsonMappingException
    {
        PropertySerializerMap.SerializerAndMapResult result = map.findAndAddSerializer(type, provider, property);
        if (map != result.map) {
            _dynamicValueSerializers = result.map;
        }
        return result.serializer;
    }
}
