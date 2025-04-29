/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.agent.tools;

import static org.opensearch.agent.tools.AbstractRetrieverTool.INPUT_FIELD;

import java.sql.*;
import java.util.Map;

import org.opensearch.client.Client;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.ml.common.spi.tools.Parser;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.spi.tools.ToolAnnotation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Setter
@Getter
@ToolAnnotation(SpreadsheetTool.TYPE)
public class SpreadsheetTool implements Tool {
    public static final String TYPE = "SpreadsheetTool";
    public static String DEFAULT_DESCRIPTION =
        "Use this tool to query structured book data from a CSV file using SQL. It provides metadata like Title, Author, Genre, Year, and Price. Ideal for filtering books by specific attributes (e.g., genre or year). Input must be a valid SQL query.";
    public static final String CSV_DIRECTORY_FIELD = "csv_directory";
    private String name = TYPE;
    private String description = DEFAULT_DESCRIPTION;
    private String csvDirectory;
    private Client client;
    private NamedXContentRegistry xContentRegistry;
    @Setter
    private Parser inputParser;
    @Setter
    private Parser outputParser;

    @Builder
    public SpreadsheetTool(Client client, NamedXContentRegistry xContentRegistry, String csvDirectory) {
        this.client = client;
        this.xContentRegistry = xContentRegistry;
        this.csvDirectory = csvDirectory;
    }

    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        if (!this.validate(parameters)) {
            throw new IllegalArgumentException("[" + INPUT_FIELD + "] is null or empty, can not process it.");
        }

        String sqlQuery = parameters.get(INPUT_FIELD);
        log.info("Executing SQL query: " + sqlQuery);
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            // Load the CSV JDBC driver
            Class.forName("org.relique.jdbc.csv.CsvDriver");

            // Create a connection to the CSV directory
            conn = DriverManager.getConnection("jdbc:relique:csv:" + csvDirectory);

            // Create a statement to execute the SQL query
            stmt = conn.createStatement();
            rs = stmt.executeQuery(sqlQuery);

            // Process the result set based on the requested format
            T result;
            result = (T) processResultSetAsJson(rs);
            listener.onResponse(result);
        } catch (Exception e) {
            log.error("Failed to execute SQL query on CSV file", e);
            listener.onFailure(new IllegalArgumentException("Failed to execute SQL query: " + e.getMessage()));
        } finally {
            // Close resources
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (conn != null)
                    conn.close();
            } catch (SQLException e) {
                log.error("Failed to close database resources", e);
            }
        }
    }

    private String processResultSetAsJson(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        JsonArray jsonArray = new JsonArray();
        Gson gson = new Gson();

        while (rs.next()) {
            JsonObject jsonObject = new JsonObject();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                String value = rs.getString(i);
                jsonObject.addProperty(columnName, value);
            }
            jsonArray.add(jsonObject);
        }

        return gson.toJson(jsonArray);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getVersion() {
        return null;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void setName(String s) {
        this.name = s;
    }

    public boolean validate(Map<String, String> parameters) {
        if (parameters == null || parameters.size() == 0) {
            return false;
        }
        String query = parameters.get(INPUT_FIELD);
        return query != null && !query.trim().isEmpty();
    }

    public static class Factory implements Tool.Factory<SpreadsheetTool> {
        private Client client;
        private NamedXContentRegistry xContentRegistry;

        private static Factory INSTANCE;

        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (SpreadsheetTool.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new Factory();
                return INSTANCE;
            }
        }

        public void init(Client client, NamedXContentRegistry xContentRegistry) {
            this.client = client;
            this.xContentRegistry = xContentRegistry;
        }

        @Override
        public SpreadsheetTool create(Map<String, Object> params) {
            String csvDirectory = (String) params.get(CSV_DIRECTORY_FIELD);
            if (csvDirectory == null || csvDirectory.isEmpty()) {
                throw new IllegalArgumentException("CSV directory must be provided");
            }

            return SpreadsheetTool.builder().client(client).xContentRegistry(xContentRegistry).csvDirectory(csvDirectory).build();
        }

        @Override
        public String getDefaultDescription() {
            return DEFAULT_DESCRIPTION;
        }

        @Override
        public String getDefaultType() {
            return TYPE;
        }

        @Override
        public String getDefaultVersion() {
            return null;
        }
    }
}
