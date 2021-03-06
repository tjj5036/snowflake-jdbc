/*
 * Copyright (c) 2012-2018 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import net.snowflake.common.core.SqlState;
import net.snowflake.common.util.ClassUtil;
import net.snowflake.common.util.FixedViewColumn;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import net.snowflake.client.log.SFLogger;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
/**
 *
 * @author jhuang
 */
public class SnowflakeUtil
{

  /**
   * Additional data types not covered by standard JDBC
   */
  public final static int EXTRA_TYPES_TIMESTAMP_LTZ = 50000;

  public final static int EXTRA_TYPES_TIMESTAMP_TZ = 50001;

  /**
   * Check the error in the JSON node and generate an exception based on
   * information extracted from the node.
   *
   * @param rootNode json object contains error information
   * @throws SnowflakeSQLException the exception get from the error in the json
   */
  static public void checkErrorAndThrowException(JsonNode rootNode)
          throws SnowflakeSQLException
  {
    // no need to throw exception if success
    if (rootNode.path("success").asBoolean())
    {
      return;
    }

    String errorMessage;
    String sqlState;
    int errorCode;
    String queryId = "unknown";

    // if we have sqlstate in data, it's a sql error
    if (!rootNode.path("data").path("sqlState").isMissingNode())
    {
      sqlState = rootNode.path("data").path("sqlState").asText();
      errorCode = rootNode.path("data").path("errorCode").asInt();
      queryId = rootNode.path("data").path("queryId").asText();
      errorMessage = rootNode.path("message").asText();
    }
    else
    {
      sqlState = SqlState.INTERNAL_ERROR; // use internal error sql state

      // check if there is an error code in the envelope
      if (!rootNode.path("code").isMissingNode())
      {
        errorCode = rootNode.path("code").asInt();
        errorMessage = rootNode.path("message").asText();
      }
      else
      {
        errorCode = ErrorCode.INTERNAL_ERROR.getMessageCode();
        errorMessage = "no_error_code_from_server";

        try
        {
          PrintWriter writer = new PrintWriter("output.json", "UTF-8");
          writer.print(rootNode.toString());
        }
        catch (Exception ex)
        {
         String s = ex.toString();
        }
      }
    }

    throw new SnowflakeSQLException(queryId, errorMessage, sqlState,
                                    errorCode);
  }

  static public SnowflakeColumnMetadata extractColumnMetadata(
                                                  JsonNode colNode,
                                                  boolean jdbcTreatDecimalAsInt)
          throws SnowflakeSQLException
  {
    String colName = colNode.path("name").asText();
    String internalColTypeName = colNode.path("type").asText();
    boolean nullable = colNode.path("nullable").asBoolean();
    int precision = colNode.path("precision").asInt();
    int scale = colNode.path("scale").asInt();
    int length = colNode.path("length").asInt();
    boolean fixed = colNode.path("fixed").asBoolean();
    String extColTypeName;

    int colType;

    SnowflakeType baseType = SnowflakeType.fromString(internalColTypeName);

    switch (baseType)
    {

      case TEXT:
        colType = Types.VARCHAR;
        extColTypeName = "VARCHAR";
        break;

      case CHAR:
        colType = Types.CHAR;
        extColTypeName = "CHAR";
        break;

      case INTEGER:
        colType = Types.INTEGER;
        extColTypeName = "INTEGER";
        break;

      case FIXED:
        colType = jdbcTreatDecimalAsInt && scale == 0
            ? Types.BIGINT : Types.DECIMAL;
        extColTypeName = "NUMBER";
        break;

      case REAL:
        colType = Types.DOUBLE;
        extColTypeName = "DOUBLE";
        break;

      case TIMESTAMP:
      case TIMESTAMP_LTZ:
        colType = EXTRA_TYPES_TIMESTAMP_LTZ;
        extColTypeName = "TIMESTAMPLTZ";
        break;

      case TIMESTAMP_NTZ:
        colType = Types.TIMESTAMP;
        extColTypeName = "TIMESTAMPNTZ";
        break;

      case TIMESTAMP_TZ:

        colType = EXTRA_TYPES_TIMESTAMP_TZ;
        extColTypeName = "TIMESTAMPTZ";
        break;

      case DATE:
        colType = Types.DATE;
        extColTypeName = "DATE";
        break;

      case TIME:
        colType = Types.TIME;
        extColTypeName = "TIME";
        break;

      case BOOLEAN:
        colType = Types.BOOLEAN;
        extColTypeName = "BOOLEAN";
        break;

      case ARRAY:
        colType = Types.VARCHAR;
        extColTypeName = "ARRAY";
        break;

      case OBJECT:
        colType = Types.VARCHAR;
        extColTypeName = "OBJECT";
        break;

      case VARIANT:
        colType = Types.VARCHAR;
        extColTypeName = "VARIANT";
        break;

      case BINARY:
        colType = Types.BINARY;
        extColTypeName = "BINARY";
        break;

      default:
        throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                                        ErrorCode.INTERNAL_ERROR
                                        .getMessageCode(),
                                        "Unknown column type: " + internalColTypeName);
    }

    String colSrcDatabase = colNode.path("database").asText();
    String colSrcSchema = colNode.path("schema").asText();
    String colSrcTable = colNode.path("table").asText();

    return new SnowflakeColumnMetadata(colName, colType, nullable, length,
                                       precision, scale, extColTypeName,
                                       fixed, baseType, colSrcDatabase,
                                       colSrcSchema, colSrcTable);
  }

  public static String javaTypeToSFTypeString(int javaType)
          throws SnowflakeSQLException
  {
    return SnowflakeType.javaTypeToSFType(javaType).name();
  }

  public static SnowflakeType javaTypeToSFType(int javaType)
          throws SnowflakeSQLException
  {
    return SnowflakeType.javaTypeToSFType(javaType);
  }

  /**
   * A small function for concatenating two file paths by making sure one and
   * only one path separator is placed between the two paths.
   * <p>
   * This is necessary since for S3 file name, having different number of file
   * separators in a path will mean different files.
   * </p>
   * <p>
   * Typical use case is to concatenate a file name to a directory.
   * </p>
   * @param leftPath left path
   * @param rightPath right path
   * @param fileSep file separator
   * @return concatenated file path
   */
  static public String concatFilePathNames(String leftPath,
                                           String rightPath,
                                           String fileSep)
  {
    String leftPathTrimmed = leftPath.trim();
    String rightPathTrimmed = rightPath.trim();
    
    if (leftPathTrimmed.isEmpty())
    {
      return rightPath;
    }

    if (leftPathTrimmed.endsWith(fileSep)
        && rightPathTrimmed.startsWith(fileSep))
    {
      return leftPathTrimmed + rightPathTrimmed.substring(1);
    }
    else if (!leftPathTrimmed.endsWith(fileSep)
             && !rightPathTrimmed.startsWith(fileSep))
    {
      return leftPathTrimmed + fileSep + rightPathTrimmed;
    }
    else
    {
      return leftPathTrimmed + rightPathTrimmed;
    }
  }

  static public String greatestCommonPrefix(String val1, String val2)
  {
    if (val1 == null || val2 == null)
    {
      return null;
    }

    StringBuilder greatestCommonPrefix = new StringBuilder();

    int len = Math.min(val1.length(), val2.length());

    for (int idx = 0; idx < len; idx++)
    {
      if (val1.charAt(idx) == val2.charAt(idx))
      {
        greatestCommonPrefix.append(val1.charAt(idx));
      }
      else
      {
        break;
      }
    }

    return greatestCommonPrefix.toString();
  }

  public static List<SnowflakeColumnMetadata> describeFixedViewColumns(
          Class clazz) throws SnowflakeSQLException
  {
    Field[] columns
            = ClassUtil.getAnnotatedDeclaredFields(clazz, FixedViewColumn.class,
                                                   true);

    Arrays.sort(columns, new FixedViewColumn.OrdinalComparatorForFields());

    List<SnowflakeColumnMetadata> rowType = new ArrayList<SnowflakeColumnMetadata>();

    for (Field column : columns)
    {
      FixedViewColumn columnAnnotation
                      = column.getAnnotation(FixedViewColumn.class);

      String typeName;
      int colType;

      Class<?> type = column.getType();
      SnowflakeType stype = SnowflakeType.TEXT;


      if (type == Integer.TYPE)
      {
        colType = Types.INTEGER;
        typeName = "INTEGER";
        stype = SnowflakeType.INTEGER;
      }
      if (type == Long.TYPE)
      {
        colType = Types.DECIMAL;
        typeName = "DECIMAL";
        stype = SnowflakeType.INTEGER;
      }
      else if (type == String.class)
      {
        colType = Types.VARCHAR;
        typeName = "VARCHAR";
        stype = SnowflakeType.TEXT;
      }
      else
      {
        throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                                        ErrorCode.INTERNAL_ERROR
                                        .getMessageCode(),
                                        "Unsupported column type: " + type
                                        .getName());
      }

      // TODO: we hard code some of the values below but can change them
      // later to derive from annotation as well.
      rowType.add(new SnowflakeColumnMetadata(
              columnAnnotation.name(), // column name
              colType, // column type
              false, // nullable
              20480, // length
              10, // precision
              0, // scale
              typeName, // type name
              true,
              stype,  // fixed
              "",     // database
              "",     // schema
              ""));   // table
    }

    return rowType;
  }

  /**
   * A utility to log response details.
   * <p>
   * Used when there is an error in http response
   * </p>
   * @param response http response get from server
   * @param logger logger object
   */
  static public void logResponseDetails(HttpResponse response, SFLogger logger)
  {
    if (response == null)
    {
      logger.error("null response");
      return;
    }

    // log the response
    if (response.getStatusLine() != null)
    {
      logger.error("Response status line reason: {}",
                 response.getStatusLine().getReasonPhrase());
    }

    // log each header from response
    Header[] headers = response.getAllHeaders();
    if (headers != null)
    {
      for (Header header : headers)
      {
        logger.error("Header name: {}, value: {}",
                   new Object[]
                   {
                     header.getName(), header.getValue()
                   });
      }
    }

    // log response
    if (response.getEntity() != null)
    {
      try
      {
        StringWriter writer = new StringWriter();
        BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader((response.getEntity().getContent())));
        IOUtils.copy(bufferedReader, writer);
        logger.error("Response content: {}", writer.toString());
      }
      catch (IOException ex)
      {
        logger.error("Failed to read content due to exception: "
                               + "{}", ex.getMessage());
      }
    }
  }

  /**
   * Returns a new thread pool configured with the default settings.
   *
   * @param threadNamePrefix prefix of the thread name
   * @param parallel the number of concurrency
   * @return A new thread pool configured with the default settings.
   */
  static public ThreadPoolExecutor createDefaultExecutorService(
      final String threadNamePrefix, final int parallel)
  {
    ThreadFactory threadFactory = new ThreadFactory() {
      private int threadCount = 1;

      public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setName(threadNamePrefix + threadCount++);
        return thread;
      }
    };
    return (ThreadPoolExecutor) Executors.newFixedThreadPool(parallel,
        threadFactory);
  }

  static public Throwable getRootCause(Exception ex)
  {
    Throwable cause = ex;
    while(cause.getCause() != null)
    {
      cause = cause.getCause();
    }

    return cause;
  }

  static public boolean isBlank(String input)
  {
    if ("".equals(input) || input == null)
    {
      return true;
    }

    for(char c : input.toCharArray())
    {
      if (!Character.isWhitespace(c))
      {
        return false;
      }
    }

    return true;
  }

  private static final String ALPHA_NUMERIC_STRING =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

  public static String randomAlphaNumeric(int count)
  {
    StringBuilder builder = new StringBuilder();
    Random random = new Random();
    while (count-- != 0)
    {
      int character = random.nextInt(ALPHA_NUMERIC_STRING.length());
      builder.append(ALPHA_NUMERIC_STRING.charAt(character));
    }
    return builder.toString();
  }
}
