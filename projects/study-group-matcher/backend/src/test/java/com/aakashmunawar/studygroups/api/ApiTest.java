package com.aakashmunawar.studygroups.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end API tests: real HTTP requests through Spring Security with real
 * JWTs, against the H2 database with the real Rutgers course seed. Each test
 * runs in a transaction that is rolled back afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiTest {

    @Autowired
    MockMvc mvc;

    // --- helpers ------------------------------------------------------------------

    record User(String token, long id) {
    }

    static String json(String template, Object... args) {
        return String.format(template, args).replace('\'', '"');
    }

    ResultActions send(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    User signup(String name) throws Exception {
        String email = name.toLowerCase() + "-" + UUID.randomUUID() + "@example.edu";
        String body = send(post("/api/auth/signup"), null,
                json("{'email':'%s','password':'correct horse 42','displayName':'%s'}", email, name))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new User(JsonPath.read(body, "$.token"), ((Number) JsonPath.read(body, "$.student.id")).longValue());
    }

    long courseId(String code) throws Exception {
        String body = mvc.perform(get("/api/courses").param("q", code)).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$[?(@.code == '" + code + "')].id");
        return ids.get(0);
    }

    void takes(User u, long... courses) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long c : courses) ids.append(ids.isEmpty() ? "" : ",").append(c);
        send(put("/api/me/courses"), u.token(), "{\"courseIds\":[" + ids + "]}").andExpect(status().isOk());
    }

    /** blocks: day, startMinute, endMinute triples. */
    void free(User u, int... blocks) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.length; i += 3) {
            sb.append(sb.isEmpty() ? "" : ",").append(String.format("{\"day\":%d,\"start\":%d,\"end\":%d}",
                    blocks[i], blocks[i + 1], blocks[i + 2]));
        }
        send(put("/api/me/availability"), u.token(), "{\"blocks\":[" + sb + "]}").andExpect(status().isOk());
    }

    long createGroup(User u, long course, int capacity) throws Exception {
        String body = send(post("/api/groups"), u.token(),
                json("{'courseId':%d,'name':'Study group','capacity':%d}", course, capacity))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    static int h(int hours) { return hours * 60; }

    // --- auth ---------------------------------------------------------------------

    @Test
    void signupLoginAndProfile() throws Exception {
        String email = "Ada." + UUID.randomUUID() + "@Example.edu";
        send(post("/api/auth/signup"), null, json("{'email':'%s','password':'long enough pw','displayName':'Ada'}", email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.student.email").value(email.toLowerCase()));

        String login = send(post("/api/auth/login"), null, json("{'email':'%s','password':'long enough pw'}", email.toUpperCase()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(login, "$.token");
        send(get("/api/me"), token, null).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Ada"));
    }

    @Test
    void rejectsDuplicateEmailAndBadInput() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.edu";
        String body = json("{'email':'%s','password':'long enough pw','displayName':'A'}", email);
        send(post("/api/auth/signup"), null, body).andExpect(status().isCreated());
        send(post("/api/auth/signup"), null, body).andExpect(status().isConflict());
        send(post("/api/auth/signup"), null, json("{'email':'not-an-email','password':'long enough pw','displayName':'A'}"))
                .andExpect(status().isBadRequest());
        send(post("/api/auth/signup"), null, json("{'email':'x@example.edu','password':'short','displayName':'A'}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("password")));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        User u = signup("Grace");
        String email = JsonPath.read(send(get("/api/me"), u.token(), null).andReturn().getResponse().getContentAsString(), "$.email");
        String a = send(post("/api/auth/login"), null, json("{'email':'%s','password':'wrong password'}", email))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String b = send(post("/api/auth/login"), null, json("{'email':'nobody@example.edu','password':'wrong password'}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals((String) JsonPath.read(a, "$.detail"), JsonPath.read(b, "$.detail"));
    }

    @Test
    void protectedEndpointsNeedAValidToken() throws Exception {
        send(get("/api/me"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/matches"), "not.a.jwt", null).andExpect(status().isUnauthorized());
        // A token signed with a different key is rejected too.
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlLWZyb20tYW5vdGhlci1rZXk";
        send(get("/api/me"), forged, null).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/courses")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // --- courses and profile --------------------------------------------------------

    @Test
    void courseCatalogIsTheRealRutgersSeed() throws Exception {
        mvc.perform(get("/api/courses")).andExpect(jsonPath("$.length()").value(148));
        mvc.perform(get("/api/courses").param("q", "data structures"))
                .andExpect(jsonPath("$[?(@.code == '198:112')].title").value("DATA STRUCTURES"));
    }

    @Test
    void availabilityIsNormalizedAndValidated() throws Exception {
        User u = signup("Linus");
        // Mon 9-11 and Mon 10-12 overlap: stored as one block, Mon 9-12.
        free(u, 1, h(9), h(11), 1, h(10), h(12), 3, h(14), h(15));
        send(get("/api/me"), u.token(), null)
                .andExpect(jsonPath("$.availability.length()").value(2))
                .andExpect(jsonPath("$.availability[0].start").value(h(9)))
                .andExpect(jsonPath("$.availability[0].end").value(h(12)));
        send(put("/api/me/availability"), u.token(), "{\"blocks\":[{\"day\":1,\"start\":600,\"end\":540}]}")
                .andExpect(status().isBadRequest());
        send(put("/api/me/availability"), u.token(), "{\"blocks\":[{\"day\":8,\"start\":0,\"end\":60}]}")
                .andExpect(status().isBadRequest());
        send(put("/api/me/courses"), u.token(), "{\"courseIds\":[999999]}").andExpect(status().isBadRequest());
    }

    // --- groups ---------------------------------------------------------------------

    @Test
    void groupRules() throws Exception {
        long ds = courseId("198:112");
        User ann = signup("Ann"), ben = signup("Ben"), cat = signup("Cat"), dan = signup("Dan");

        // Must take the course to create a group for it.
        send(post("/api/groups"), ann.token(), json("{'courseId':%d,'name':'G','capacity':2}", ds))
                .andExpect(status().isConflict());
        takes(ann, ds); takes(ben, ds); takes(cat, ds);
        long g = createGroup(ann, ds, 2);

        // One group per course.
        send(post("/api/groups"), ann.token(), json("{'courseId':%d,'name':'G2','capacity':3}", ds))
                .andExpect(status().isConflict());
        // Not enrolled: can't join. Enrolled: can.
        send(post("/api/groups/" + g + "/join"), dan.token(), null).andExpect(status().isConflict());
        send(post("/api/groups/" + g + "/join"), ben.token(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2));
        // Capacity 2: full.
        send(post("/api/groups/" + g + "/join"), cat.token(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This group is full"));
        // Can't drop a course while in its group.
        send(put("/api/me/courses"), ben.token(), "{\"courseIds\":[]}").andExpect(status().isConflict());

        // Owner leaves: ownership passes to the longest-standing member.
        send(post("/api/groups/" + g + "/leave"), ann.token(), null).andExpect(status().isNoContent());
        send(get("/api/groups/" + g), ben.token(), null)
                .andExpect(jsonPath("$.group.owner").value(true))
                .andExpect(jsonPath("$.members.length()").value(1));
        // Last member leaves: the group is deleted.
        send(post("/api/groups/" + g + "/leave"), ben.token(), null).andExpect(status().isNoContent());
        send(get("/api/groups/" + g), ben.token(), null).andExpect(status().isNotFound());
    }

    @Test
    void groupDetailShowsWhenAllMembersAreFree() throws Exception {
        long ds = courseId("198:112");
        User a = signup("A"), b = signup("B");
        takes(a, ds); takes(b, ds);
        free(a, 2, h(9), h(13));
        free(b, 2, h(11), h(15));
        long g = createGroup(a, ds, 4);
        send(post("/api/groups/" + g + "/join"), b.token(), null).andExpect(status().isOk());
        send(get("/api/groups/" + g), a.token(), null)
                .andExpect(jsonPath("$.commonFreeTime.length()").value(1))
                .andExpect(jsonPath("$.commonFreeTime[0].day").value(2))
                .andExpect(jsonPath("$.commonFreeTime[0].start").value(h(11)))
                .andExpect(jsonPath("$.commonFreeTime[0].end").value(h(13)));
    }

    // --- matching -------------------------------------------------------------------

    @Test
    void matchesEndToEnd() throws Exception {
        long ds = courseId("198:112"), discrete = courseId("198:205"), calc = courseId("640:151");
        User me = signup("Me"), alice = signup("Alice"), bob = signup("Bob"),
                carol = signup("Carol"), dave = signup("Dave"), erin = signup("Erin");

        takes(me, ds, discrete);
        free(me, 1, h(9), h(12), 2, h(13), h(17));

        // Alice + Bob: a 198:112 group. All three of us free only Mon 10-11 (60 min).
        takes(alice, ds); free(alice, 1, h(9), h(11));
        takes(bob, ds); free(bob, 1, h(10), h(12));
        long group = createGroup(alice, ds, 4);
        send(post("/api/groups/" + group + "/join"), bob.token(), null).andExpect(status().isOk());

        // Carol: 198:205, no group, free Tue 13-16 -> 180 min with me.
        takes(carol, discrete); free(carol, 2, h(13), h(16));
        // Dave: 198:205 but only free Wednesday -> never overlaps: not recommended.
        takes(dave, discrete); free(dave, 3, h(9), h(17));
        // Erin: overlaps perfectly but shares no course: not recommended.
        takes(erin, calc); free(erin, 1, h(9), h(12), 2, h(13), h(17));

        send(get("/api/matches"), me.token(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("PEER"))
                .andExpect(jsonPath("$[0].peer.displayName").value("Carol"))
                .andExpect(jsonPath("$[0].usableMinutes").value(180))
                .andExpect(jsonPath("$[0].sharedCourses[0].code").value("198:205"))
                .andExpect(jsonPath("$[1].kind").value("GROUP"))
                .andExpect(jsonPath("$[1].group.id").value(group))
                .andExpect(jsonPath("$[1].usableMinutes").value(60))
                .andExpect(jsonPath("$[1].overlap[0].start").value(h(10)))
                .andExpect(jsonPath("$[1].overlap[0].end").value(h(11)));

        // Once I join Alice's group, 198:112 recommendations disappear and
        // Alice and Bob are no longer suggested as classmates for it.
        send(post("/api/groups/" + group + "/join"), me.token(), null).andExpect(status().isOk());
        send(get("/api/matches"), me.token(), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].peer.displayName").value("Carol"));
    }

    @Test
    void emailsOfOtherStudentsAreNeverExposed() throws Exception {
        long discrete = courseId("198:205");
        User me = signup("Me"), other = signup("Other");
        takes(me, discrete); takes(other, discrete);
        free(me, 4, h(9), h(12)); free(other, 4, h(9), h(12));
        String body = send(get("/api/matches"), me.token(), null).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("@example.edu"), body);
    }

    // --- CORS -----------------------------------------------------------------------

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/me").header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"));
        mvc.perform(options("/api/me").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
