/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.dropwizard.testing.junit5.ResourceExtension;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.whispersystems.textsecuregcm.auth.ExternalServiceCredentials;
import org.whispersystems.textsecuregcm.auth.ExternalServiceCredentialsGenerator;
import org.whispersystems.textsecuregcm.entities.AuthCheckRequest;
import org.whispersystems.textsecuregcm.entities.AuthCheckResponse;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.util.MutableClock;

abstract class SecureValueRecoveryControllerBaseTest {

  private static final UUID USER_1 = UUID.randomUUID();

  private static final UUID USER_2 = UUID.randomUUID();

  private static final UUID USER_3 = UUID.randomUUID();

  private static final String E164_VALID = "+18005550123";

  private static final String E164_INVALID = "1(800)555-0123";

  private final String pathPrefix;
  private final ResourceExtension resourceExtension;
  private final AccountsManager mockAccountsManager;
  private final ExternalServiceCredentialsGenerator credentialsGenerator;
  private final MutableClock clock;

  @BeforeEach
  public void before() throws Exception {
    Mockito.when(mockAccountsManager.getByE164(E164_VALID)).thenReturn(Optional.of(account(USER_1)));
  }

  protected SecureValueRecoveryControllerBaseTest(
      final String pathPrefix,
      final AccountsManager mockAccountsManager,
      final MutableClock mutableClock,
      final ResourceExtension resourceExtension,
      final ExternalServiceCredentialsGenerator credentialsGenerator) {
    this.pathPrefix = pathPrefix;
    this.resourceExtension = resourceExtension;
    this.mockAccountsManager = mockAccountsManager;
    this.credentialsGenerator = credentialsGenerator;
    this.clock = mutableClock;
  }

  @Test
  public void testOneMatch() throws Exception {
    validate(Map.of(
        token(USER_1, day(1)), AuthCheckResponse.Result.MATCH,
        token(USER_2, day(1)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(1)), AuthCheckResponse.Result.NO_MATCH
    ), day(2));
  }

  @Test
  public void testNoMatch() throws Exception {
    validate(Map.of(
        token(USER_2, day(1)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(1)), AuthCheckResponse.Result.NO_MATCH
    ), day(2));
  }

  @Test
  public void testSomeInvalid() throws Exception {
    final ExternalServiceCredentials user1Cred = credentials(USER_1, day(1));
    final ExternalServiceCredentials user2Cred = credentials(USER_2, day(1));
    final ExternalServiceCredentials user3Cred = credentials(USER_3, day(1));

    final String fakeToken = token(new ExternalServiceCredentials(user2Cred.username(), user3Cred.password()));
    validate(Map.of(
        token(user1Cred), AuthCheckResponse.Result.MATCH,
        token(user2Cred), AuthCheckResponse.Result.NO_MATCH,
        fakeToken, AuthCheckResponse.Result.INVALID
    ), day(2));
  }

  @Test
  public void testSomeExpired() throws Exception {
    validate(Map.of(
        token(USER_1, day(100)), AuthCheckResponse.Result.MATCH,
        token(USER_2, day(100)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(10)), AuthCheckResponse.Result.INVALID,
        token(USER_3, day(20)), AuthCheckResponse.Result.INVALID
    ), day(110));
  }

  @Test
  public void testSomeHaveNewerVersions() throws Exception {
    validate(Map.of(
        token(USER_1, day(10)), AuthCheckResponse.Result.INVALID,
        token(USER_1, day(20)), AuthCheckResponse.Result.MATCH,
        token(USER_2, day(10)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(20)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(10)), AuthCheckResponse.Result.INVALID
    ), day(25));
  }

  private void validate(
      final Map<String, AuthCheckResponse.Result> expected,
      final long nowMillis) throws Exception {
    clock.setTimeMillis(nowMillis);
    final AuthCheckRequest request = new AuthCheckRequest(E164_VALID, List.copyOf(expected.keySet()));
    final Response response = resourceExtension.getJerseyTest().target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity(request, MediaType.APPLICATION_JSON));
    try (response) {
      final AuthCheckResponse res = response.readEntity(AuthCheckResponse.class);
      assertEquals(200, response.getStatus());
      assertEquals(expected, res.matches());
    }
  }

  @Test
  public void testHttpResponseCodeSuccess() throws Exception {
    final Map<String, AuthCheckResponse.Result> expected = Map.of(
        token(USER_1, day(10)), AuthCheckResponse.Result.INVALID,
        token(USER_1, day(20)), AuthCheckResponse.Result.MATCH,
        token(USER_2, day(10)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(20)), AuthCheckResponse.Result.NO_MATCH,
        token(USER_3, day(10)), AuthCheckResponse.Result.INVALID
    );

    clock.setTimeMillis(day(25));

    final AuthCheckRequest in = new AuthCheckRequest(E164_VALID, List.copyOf(expected.keySet()));

    final Response response = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity(in, MediaType.APPLICATION_JSON));

    try (response) {
      final AuthCheckResponse res = response.readEntity(AuthCheckResponse.class);
      assertEquals(200, response.getStatus());
      assertEquals(expected, res.matches());
    }
  }

  @Test
  public void testHttpResponseCodeWhenInvalidNumber() throws Exception {
    final AuthCheckRequest in = new AuthCheckRequest(E164_INVALID, Collections.singletonList("1"));
    final Response response = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity(in, MediaType.APPLICATION_JSON));

    try (response) {
      assertEquals(422, response.getStatus());
    }
  }

  @Test
  public void testHttpResponseCodeWhenTooManyTokens() throws Exception {
    final AuthCheckRequest inOkay = new AuthCheckRequest(E164_VALID, List.of(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"
    ));
    final AuthCheckRequest inTooMany = new AuthCheckRequest(E164_VALID, List.of(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"
    ));
    final AuthCheckRequest inNoTokens = new AuthCheckRequest(E164_VALID, Collections.emptyList());

    final Response responseOkay = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity(inOkay, MediaType.APPLICATION_JSON));

    final Response responseError1 = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity(inTooMany, MediaType.APPLICATION_JSON));

    final Response responseError2 = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity(inNoTokens, MediaType.APPLICATION_JSON));

    try (responseOkay; responseError1; responseError2) {
      assertEquals(200, responseOkay.getStatus());
      assertEquals(422, responseError1.getStatus());
      assertEquals(422, responseError2.getStatus());
    }
  }

  @Test
  public void testHttpResponseCodeWhenPasswordsMissing() throws Exception {
    final Response response = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity("""
            {
              "number": "123"
            }
            """, MediaType.APPLICATION_JSON));

    try (response) {
      assertEquals(422, response.getStatus());
    }
  }

  @Test
  public void testHttpResponseCodeWhenNumberMissing() throws Exception {
    final Response response = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity("""
            {
              "passwords": ["aaa:bbb"]
            }
            """, MediaType.APPLICATION_JSON));

    try (response) {
      assertEquals(422, response.getStatus());
    }
  }

  @Test
  public void testHttpResponseCodeWhenExtraFields() throws Exception {
    final Response response = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity("""
            {
              "number": "+18005550123",
              "passwords": ["aaa:bbb"],
              "unexpected": "value"
            }
            """, MediaType.APPLICATION_JSON));

    try (response) {
      assertEquals(200, response.getStatus());
    }
  }

  @Test
  public void testHttpResponseCodeWhenNotAJson() throws Exception {
    final Response response = resourceExtension.getJerseyTest()
        .target(pathPrefix + "/backup/auth/check")
        .request()
        .post(Entity.entity("random text", MediaType.APPLICATION_JSON));

    try (response) {
      assertEquals(400, response.getStatus());
    }
  }

  private String token(final UUID uuid, final long timeMillis) {
    return token(credentials(uuid, timeMillis));
  }

  private static String token(final ExternalServiceCredentials credentials) {
    return credentials.username() + ":" + credentials.password();
  }

  private ExternalServiceCredentials credentials(final UUID uuid, final long timeMillis) {
    clock.setTimeMillis(timeMillis);
    return credentialsGenerator.generateForUuid(uuid);
  }

  private static long day(final int n) {
    return TimeUnit.DAYS.toMillis(n);
  }

  private static Account account(final UUID uuid) {
    final Account a = new Account();
    a.setUuid(uuid);
    return a;
  }
}
