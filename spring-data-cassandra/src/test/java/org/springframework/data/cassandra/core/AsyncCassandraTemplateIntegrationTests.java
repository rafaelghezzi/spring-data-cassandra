/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.core;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;
import org.springframework.cassandra.core.AsyncCqlTemplate;
import org.springframework.cassandra.test.integration.AbstractKeyspaceCreatingIntegrationTest;
import org.springframework.data.cassandra.convert.MappingCassandraConverter;
import org.springframework.data.cassandra.domain.Person;
import org.springframework.data.cassandra.test.integration.support.SchemaTestUtils;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * Integration tests for {@link AsyncCassandraTemplate}.
 *
 * @author Mark Paluch
 */
public class AsyncCassandraTemplateIntegrationTests extends AbstractKeyspaceCreatingIntegrationTest {

	private AsyncCassandraTemplate template;

	@Before
	public void setUp() throws Exception {

		MappingCassandraConverter converter = new MappingCassandraConverter();
		CassandraTemplate cassandraTemplate = new CassandraTemplate(session, converter);
		template = new AsyncCassandraTemplate(new AsyncCqlTemplate(session), converter);

		SchemaTestUtils.potentiallyCreateTableFor(Person.class, cassandraTemplate);
		SchemaTestUtils.truncate(Person.class, cassandraTemplate);
	}

	@Test // DATACASS-292
	public void insertShouldInsertEntity() {

		Person person = new Person("heisenberg", "Walter", "White");

		assertThat(getUninterruptibly(template.selectOneById(person.getId(), Person.class))).isNull();

		ListenableFuture<Person> insert = template.insert(person);

		assertThat(getUninterruptibly(insert)).isEqualTo(person);
		assertThat(getUninterruptibly(template.selectOneById(person.getId(), Person.class))).isEqualTo(person);
	}

	@Test // DATACASS-292
	public void shouldInsertAndCountEntities() throws Exception {

		Person person = new Person("heisenberg", "Walter", "White");

		getUninterruptibly(template.insert(person));

		ListenableFuture<Long> count = template.count(Person.class);
		assertThat(getUninterruptibly(count)).isEqualTo(1L);
	}

	@Test // DATACASS-292
	public void updateShouldUpdateEntity() throws Exception {

		Person person = new Person("heisenberg", "Walter", "White");
		getUninterruptibly(template.insert(person));

		person.setFirstname("Walter Hartwell");
		Person updated = getUninterruptibly(template.update(person));

		assertThat(updated).isNotNull();
		assertThat(getUninterruptibly(template.selectOneById(person.getId(), Person.class))).isEqualTo(person);
	}

	@Test // DATACASS-292
	public void deleteShouldRemoveEntity() throws Exception {

		Person person = new Person("heisenberg", "Walter", "White");
		getUninterruptibly(template.insert(person));

		Person deleted = getUninterruptibly(template.delete(person));

		assertThat(deleted).isNotNull();
		assertThat(getUninterruptibly(template.selectOneById(person.getId(), Person.class))).isNull();
	}

	@Test // DATACASS-292
	public void deleteByIdShouldRemoveEntity() throws Exception {

		Person person = new Person("heisenberg", "Walter", "White");
		getUninterruptibly(template.insert(person));

		Boolean deleted = getUninterruptibly(template.deleteById(person.getId(), Person.class));
		assertThat(deleted).isTrue();

		assertThat(getUninterruptibly(template.selectOneById(person.getId(), Person.class))).isNull();
	}

	private static <T> T getUninterruptibly(Future<T> future) {

		try {
			return future.get();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
