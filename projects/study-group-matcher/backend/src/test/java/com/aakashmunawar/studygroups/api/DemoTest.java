package com.aakashmunawar.studygroups.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aakashmunawar.studygroups.repo.StudentRepository;
import com.aakashmunawar.studygroups.service.DemoSeeder;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** The demo sandbox: one-click login, a useful starting state, and isolation from real students. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DemoTest {

    @Autowired MockMvc mvc;
    @Autowired DemoSeeder seeder;
    @Autowired StudentRepository students;

    String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    String demoToken() throws Exception {
        return login(DemoSeeder.DEMO_EMAIL, DemoSeeder.DEMO_PASSWORD);
    }

    String realStudent(long... courseIds) throws Exception {
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"real-" + UUID.randomUUID() + "@example.edu\",\"password\":\"real password\",\"displayName\":\"Real\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        StringBuilder ids = new StringBuilder();
        for (long c : courseIds) ids.append(ids.isEmpty() ? "" : ",").append(c);
        mvc.perform(put("/api/me/courses").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseIds\":[" + ids + "]}")).andExpect(status().isOk());
        // Free exactly when every demo classmate is: Mon-Fri 9am-6pm.
        StringBuilder blocks = new StringBuilder();
        for (int d = 1; d <= 5; d++) blocks.append(d > 1 ? "," : "").append("{\"day\":" + d + ",\"start\":540,\"end\":1080}");
        mvc.perform(put("/api/me/availability").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"blocks\":[" + blocks + "]}")).andExpect(status().isOk());
        return token;
    }

    long courseId(String code) throws Exception {
        String body = mvc.perform(get("/api/courses").param("q", code)).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$[?(@.code == '" + code + "')].id");
        return ids.get(0);
    }

    long demoGroupId(String token, String courseCode) throws Exception {
        String body = mvc.perform(get("/api/groups").param("courseId", String.valueOf(courseId(courseCode)))
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$[0].id")).longValue();
    }

    @Test
    void oneClickCredentialsWorkAndTheAppIsNotEmpty() throws Exception {
        String t = demoToken();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.courses.length()").value(3))
                .andExpect(jsonPath("$.availability.length()").value(5));
        mvc.perform(get("/api/groups/mine").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Discrete math study hall (demo)"));
        // Checked by hand against DemoSeeder's schedules:
        //  1. Priya: in a 198:112 group but not a 640:250 one, so she's a classmate
        //     match for 640:250; free together Mon 11-1, Thu 3-5, Fri 1-3 = 360 min.
        //  2. Linear algebra group: Tue 2-4:30 + Wed 10-12 = 270 min.
        //  3. Noah and 4. the Data Structures group both have 240 min with a
        //     120-min longest block; Noah shares two courses, so he ranks first.
        mvc.perform(get("/api/matches").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].peer.displayName").value("Priya (demo)"))
                .andExpect(jsonPath("$[0].usableMinutes").value(360))
                .andExpect(jsonPath("$[1].group.name").value("Linear algebra problem sets (demo)"))
                .andExpect(jsonPath("$[1].usableMinutes").value(270))
                .andExpect(jsonPath("$[2].peer.displayName").value("Noah (demo)"))
                .andExpect(jsonPath("$[2].sharedCourses.length()").value(2))
                .andExpect(jsonPath("$[2].usableMinutes").value(240))
                .andExpect(jsonPath("$[3].group.name").value("Data Structures review (demo)"))
                .andExpect(jsonPath("$[3].usableMinutes").value(240));
    }

    @Test
    void realStudentsNeverSeeTheDemoSandbox() throws Exception {
        String real = realStudent(courseId("198:112"), courseId("640:250"), courseId("198:205"));
        // Same courses and free all week, yet no demo group or classmate shows up.
        mvc.perform(get("/api/matches").header("Authorization", "Bearer " + real))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/groups").param("courseId", String.valueOf(courseId("198:112")))
                .header("Authorization", "Bearer " + real)).andExpect(jsonPath("$.length()").value(0));
        long demoGroup = demoGroupId(demoToken(), "198:112");
        mvc.perform(get("/api/groups/" + demoGroup).header("Authorization", "Bearer " + real))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/groups/" + demoGroup + "/join").header("Authorization", "Bearer " + real))
                .andExpect(status().isNotFound());
    }

    @Test
    void theDemoAccountNeverSeesRealStudents() throws Exception {
        String real = realStudent(courseId("198:112"));
        mvc.perform(post("/api/groups").header("Authorization", "Bearer " + real).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":" + courseId("198:112") + ",\"name\":\"Real group\",\"capacity\":4}"))
                .andExpect(status().isCreated());
        String demo = demoToken();
        String groupsBody = mvc.perform(get("/api/groups").param("courseId", String.valueOf(courseId("198:112")))
                .header("Authorization", "Bearer " + demo)).andReturn().getResponse().getContentAsString();
        assertFalse(groupsBody.contains("Real group"), groupsBody);
        String matches = mvc.perform(get("/api/matches").header("Authorization", "Bearer " + demo))
                .andReturn().getResponse().getContentAsString();
        assertFalse(matches.contains("Real"), matches);
    }

    @Test
    void reservedDomainAndDemoClassmatesCannotLogIn() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"password\":\"long enough\",\"displayName\":\"X\"}"))
                .andExpect(status().isBadRequest());
        for (String pw : List.of("demo1234", "!demo-account-without-login", "")) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"priya.demo@example.com\",\"password\":\"" + pw + "\"}"))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void resetRestoresTheSandboxAndLeavesRealAccountsAlone() throws Exception {
        String real = realStudent(courseId("198:112"));
        long before = students.count();
        String demo = demoToken();
        // A visitor joins a group and wipes the demo's free time...
        mvc.perform(post("/api/groups/" + demoGroupId(demo, "198:112") + "/join").header("Authorization", "Bearer " + demo))
                .andExpect(status().isOk());
        mvc.perform(put("/api/me/availability").header("Authorization", "Bearer " + demo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"blocks\":[]}")).andExpect(status().isOk());

        seeder.reset();   // what happens on the next startup

        String fresh = demoToken();
        mvc.perform(get("/api/groups/mine").header("Authorization", "Bearer " + fresh)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + fresh)).andExpect(jsonPath("$.availability.length()").value(5));
        assertEquals(before, students.count());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + real)).andExpect(status().isOk());
    }
}
