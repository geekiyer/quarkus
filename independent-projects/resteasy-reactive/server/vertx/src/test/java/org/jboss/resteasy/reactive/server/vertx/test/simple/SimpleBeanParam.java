package org.jboss.resteasy.reactive.server.vertx.test.simple;

import java.util.List;
import java.util.Optional;

import javax.ws.rs.BeanParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.QueryParam;

import org.junit.jupiter.api.Assertions;

public class SimpleBeanParam {
    @QueryParam("query")
    String query;

    @QueryParam("query")
    private String privateQuery;

    @QueryParam("query")
    protected String protectedQuery;

    @QueryParam("query")
    public String publicQuery;

    @HeaderParam("header")
    String header;

    @BeanParam
    OtherBeanParam otherBeanParam;

    @QueryParam("queryList")
    List<String> queryList;

    @QueryParam("query")
    ParameterWithFromString parameterWithFromString;

    @QueryParam("missing")
    String missing;

    @DefaultValue("there")
    @QueryParam("missing")
    String missingWithDefaultValue;

    @QueryParam("missing")
    ParameterWithFromString missingParameterWithFromString;

    @DefaultValue("there")
    @QueryParam("missing")
    ParameterWithFromString missingParameterWithFromStringAndDefaultValue;

    @QueryParam("int")
    int primitiveParam;

    @QueryParam("missing")
    int missingPrimitiveParam;

    @DefaultValue("42")
    @QueryParam("missing")
    int missingPrimitiveParamWithDefaultValue;

    @QueryParam("query")
    MyParameter myParameter;

    @QueryParam("query")
    List<MyParameter> myParameterList;

    @QueryParam("missing")
    Optional<String> missingOptionalString;

    @QueryParam("query")
    Optional<String> optionalQuery;

    @QueryParam("query")
    Optional<ParameterWithFromString> optionalParameterWithFromString;

    public void check(String path) {
        Assertions.assertEquals("one-query", query);
        Assertions.assertEquals("one-query", privateQuery);
        Assertions.assertEquals("one-query", protectedQuery);
        Assertions.assertEquals("one-query", publicQuery);
        Assertions.assertEquals("one-header", header);
        Assertions.assertNotNull(otherBeanParam);
        otherBeanParam.check(path);
        Assertions.assertNotNull(queryList);
        Assertions.assertEquals("one", queryList.get(0));
        Assertions.assertEquals("two", queryList.get(1));
        Assertions.assertNotNull(parameterWithFromString);
        Assertions.assertEquals("ParameterWithFromString[val=one-query]", parameterWithFromString.toString());
        Assertions.assertNull(missing);
        Assertions.assertEquals("there", missingWithDefaultValue);
        Assertions.assertNull(missingParameterWithFromString);
        Assertions.assertEquals("ParameterWithFromString[val=there]", missingParameterWithFromStringAndDefaultValue.toString());
        Assertions.assertEquals(666, primitiveParam);
        Assertions.assertEquals(0, missingPrimitiveParam);
        Assertions.assertEquals(42, missingPrimitiveParamWithDefaultValue);
        Assertions.assertEquals("one-queryone-query", myParameter.toString());
        Assertions.assertEquals("one-queryone-query", myParameterList.get(0).toString());
        Assertions.assertTrue(missingOptionalString.isEmpty());
        Assertions.assertEquals("one-query", optionalQuery.get());
        Assertions.assertEquals("ParameterWithFromString[val=one-query]", optionalParameterWithFromString.get().toString());
    }
}
