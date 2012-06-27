package com.fasterxml.jackson.datatype.guava;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.TreeMultimap;
import static com.google.common.collect.TreeMultimap.create;
import java.util.Collection;

/**
 * Unit tests to verify handling of various {@link Multimap}s.
 *
 * @author steven@nesscomputing.com
 */
public class TestMultimaps extends BaseTest
{
	private final ObjectMapper MAPPER = mapperWithModule();

	public void testMultimap() throws Exception
	{
		_testMultimap(TreeMultimap.create(), true,
			"{\"false\":[false],\"maybe\":[false,true],\"true\":[true]}");
		_testMultimap(LinkedListMultimap.create(), false,
			"{\"true\":[true],\"false\":[false],\"maybe\":[true,false]}");
		_testMultimap(LinkedHashMultimap.create(), false, null);
	}

	private void _testMultimap(Multimap<?, ?> map0, boolean fullyOrdered, String EXPECTED) throws Exception
	{
		@SuppressWarnings("unchecked")
		Multimap<String, Boolean> map = (Multimap<String, Boolean>) map0;
		map.put("true", Boolean.TRUE);
		map.put("false", Boolean.FALSE);
		map.put("maybe", Boolean.TRUE);
		map.put("maybe", Boolean.FALSE);

		// Test that typed writes work
		if (EXPECTED != null) {
			String json = MAPPER.writerWithType(new TypeReference<Multimap<String, Boolean>>() {}).writeValueAsString(map);
			assertEquals(EXPECTED, json);
		}

		// And untyped too
		String serializedForm = MAPPER.writeValueAsString(map);

		if (EXPECTED != null) {
			assertEquals(EXPECTED, serializedForm);
		}

		// these seem to be order-sensitive as well, so only use for ordered-maps
		if (fullyOrdered) {
			assertEquals(map, MAPPER.<Multimap<String, Boolean>>readValue(serializedForm, new TypeReference<TreeMultimap<String, Boolean>>() {}));
			assertEquals(map, create(MAPPER.<Multimap<String, Boolean>>readValue(serializedForm, new TypeReference<Multimap<String, Boolean>>() {})));
			assertEquals(map, create(MAPPER.<Multimap<String, Boolean>>readValue(serializedForm, new TypeReference<HashMultimap<String, Boolean>>() {})));
			assertEquals(map, create(MAPPER.<Multimap<String, Boolean>>readValue(serializedForm, new TypeReference<ImmutableMultimap<String, Boolean>>() {})));
		}
	}

	public void testMultimapIssue3() throws Exception
	{
		Multimap<String, String> m1 = TreeMultimap.create();
		m1.put("foo", "bar");
		m1.put("foo", "baz");
		m1.put("qux", "quux");
		ObjectMapper o = MAPPER;

		String t1 = o.writerWithType(new TypeReference<TreeMultimap<String, String>>() {}).writeValueAsString(m1);
		Map<?, ?> javaMap = o.readValue(t1, Map.class);
		assertEquals(2, javaMap.size());

		String t2 = o.writerWithType(new TypeReference<Multimap<String, String>>() {}).writeValueAsString(m1);
		javaMap = o.readValue(t2, Map.class);
		assertEquals(2, javaMap.size());

		TreeMultimap<String, String> m2 = TreeMultimap.create();
		m2.put("foo", "bar");
		m2.put("foo", "baz");
		m2.put("qux", "quux");

		String t3 = o.writerWithType(new TypeReference<TreeMultimap<String, String>>() {}).writeValueAsString(m2);
		javaMap = o.readValue(t3, Map.class);
		assertEquals(2, javaMap.size());

		String t4 = o.writerWithType(new TypeReference<Multimap<String, String>>() {}).writeValueAsString(m2);
		javaMap = o.readValue(t4, Map.class);
		assertEquals(2, javaMap.size());
	}

	// Example from http://programmerbruce.blogspot.com/2011/05/deserialize-json-with-jackson-into.html
	// and http://wiki.fasterxml.com/JacksonPolymorphicDeserialization

	public void testTypeInfo() throws Exception {
		final Zoo zoo = new Zoo();
		final Dog dog = new Dog("Spike", "mutt", "red");
		final Cat cat = new Cat("Fluffy", "spider ring");
		final String dogJson = MAPPER.writeValueAsString(dog);
		final Animal dogReincarnated = MAPPER.readValue(dogJson, Animal.class);
		assertEquals(dog, dogReincarnated);
		final String catJson = MAPPER.writeValueAsString(cat);
		final Animal catReincarnated = MAPPER.readValue(catJson, Animal.class);
		assertEquals(cat, catReincarnated);
		zoo.animals = ImmutableList.of(dog, cat);
		final String json1 = MAPPER.writeValueAsString(zoo);
		System.err.println("zoo json1: "+json1);
		final NameZoo nameZoo = new NameZoo();
		nameZoo.nameToAnimals = ImmutableMultimap.of(dog.name, dog, cat.name, cat);
//		nameZoo.nameToAnimals = ImmutableMap.of(dog.name, dog, cat.name, cat);
		final String json2 = MAPPER.writeValueAsString(nameZoo);
		System.err.println("nameZoo json2: "+json2);
		final NameZoo nameZoo2 = MAPPER.readValue(json2, NameZoo.class);
		// type info {- is -} was missing!
		assertEquals(nameZoo, nameZoo2);
	}

	static class NameZoo {
		NameZoo() {}
		@JsonCreator
		NameZoo(@JsonProperty("nameToAnimals") Multimap<String, Animal> nameToAnimals) {
//		NameZoo(@JsonProperty("nameToAnimals") Map<String, Animal> nameToAnimals) {
			this.nameToAnimals = nameToAnimals;
		}
		public Multimap<String, Animal> nameToAnimals;
//		public Map<String, Animal> nameToAnimals;
		@Override
		public int hashCode() {
			return Objects.hashCode(nameToAnimals);
		}
		@Override
		public boolean equals(Object obj) {
			if (obj instanceof NameZoo) {
				final NameZoo that = (NameZoo) obj;
				return this.nameToAnimals.equals(that.nameToAnimals);
			}
			return false;
		}
	}

	static class Zoo {
		public Collection<Animal> animals;
	}

	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type")
	@JsonSubTypes({
		@Type(value = Cat.class, name = "cat"),
		@Type(value = Dog.class, name = "dog")})
	static abstract class Animal {
		public String name;
	}

	static class Dog extends Animal {
		@JsonCreator
		Dog(@JsonProperty("name") final String name,
				@JsonProperty("breed") final String breed,
				@JsonProperty("leashColor") final String leashColor) {
			this.name = name;
			this.breed = breed;
			this.leashColor = leashColor;
		}
		public String breed;
		public String leashColor;
		@Override
		public int hashCode() {
			return Objects.hashCode(name, breed, leashColor);
		}
		@Override
		public boolean equals(Object obj) {
			if (obj instanceof Dog) {
				Dog that = (Dog) obj;
				return Objects.equal(this.name, that.name) &&
					Objects.equal(this.breed, that.breed) &&
					Objects.equal(this.leashColor, that.leashColor);
			}
			return false;
		}
	}

	static class Cat extends Animal {
		@JsonCreator
		Cat(@JsonProperty("name") final String name,
			@JsonProperty("favoriteToy") final String favoriteToy) {
			this.name = name;
			this.favoriteToy = favoriteToy;
		}
		public String favoriteToy;
		@Override
		public int hashCode() {
			return Objects.hashCode(name, favoriteToy);
		}
		@Override
		public boolean equals(Object obj) {
			if (obj instanceof Cat) {
				Cat that = (Cat) obj;
				return Objects.equal(this.name, that.name) &&
					Objects.equal(this.favoriteToy, that.favoriteToy);
			}
			return false;
		}
	}
}