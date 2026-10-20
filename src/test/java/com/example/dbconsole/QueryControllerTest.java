package com.example.dbconsole;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

// Uses an in-memory H2 database (MySQL mode) instead of the real MySQL.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class QueryControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void indexRenders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MySQL Query Console")));
    }

    @Test
    void ddlDmlAndSelect() throws Exception {
        mvc.perform(post("/query").param("sql", "CREATE TABLE t (id INT, name VARCHAR(20))"))
                .andExpect(status().isOk());
        mvc.perform(post("/query").param("sql", "INSERT INTO t VALUES (1, 'alice'), (2, NULL);"))
                .andExpect(content().string(containsString("2 row(s) affected")));
        mvc.perform(post("/query").param("sql", "SELECT * FROM t ORDER BY id"))
                .andExpect(content().string(containsString("alice")))
                .andExpect(content().string(containsString("NULL")))
                .andExpect(content().string(containsString("2 row(s)")));
    }

    @Test
    void sqlErrorIsShown() throws Exception {
        mvc.perform(post("/query").param("sql", "SELECT * FROM no_such_table"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Error")))
                .andExpect(content().string(containsString("NO_SUCH_TABLE")));
    }

    @Test
    void connectionInfoShown() throws Exception {
        mvc.perform(get("/connection"))
                .andExpect(content().string(containsString("Connected")))
                .andExpect(content().string(containsString("jdbc:h2:mem:testdb")));
    }
}
