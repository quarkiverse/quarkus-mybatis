package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.cursor.Cursor;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.BuildContext;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.test.QuarkusExtensionTest;

public class SerializableResultTypesTest {

    private static final String XML_MAPPER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="%s">
                <cache/>
            </mapper>
            """.formatted(XmlCachedMapper.class.getName());

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest().withConfigurationResource("application.properties")
            .setArchiveProducer(
                    () -> ShrinkWrap.create(JavaArchive.class)
                            .addClasses(CachedMapper.class, CachedResult.class, NestedResult.class,
                                    XmlCachedMapper.class, XmlCachedResult.class,
                                    ReadOnlyCachedMapper.class, ReadOnlyResult.class,
                                    UncachedMapper.class, UncachedResult.class)
                            .addAsResource(new StringAsset(XML_MAPPER), "cache/XmlCachedMapper.xml"))
            .addBuildChainCustomizer(builder -> builder.addBuildStep(SerializableResultTypesTest::verify)
                    .consumes(ReflectiveHierarchyBuildItem.class)
                    .consumes(ReflectiveClassBuildItem.class)
                    .produces(FeatureBuildItem.class)
                    .build());

    static void verify(BuildContext context) {
        Set<String> serializableHierarchy = context.consumeMulti(ReflectiveHierarchyBuildItem.class).stream()
                .filter(item -> item.getSource().startsWith("MyBatisProcessor"))
                .peek(item -> assertTrue(item.isSerialization()))
                .map(item -> item.getType().name().toString())
                .collect(Collectors.toSet());

        // Cached result types are registered, nested types are handled by the hierarchy walk
        assertEquals(Set.of(CachedResult.class.getName(), XmlCachedResult.class.getName()), serializableHierarchy);

        Set<String> serializableClasses = context.consumeMulti(ReflectiveClassBuildItem.class).stream()
                .filter(ReflectiveClassBuildItem::isSerialization)
                .flatMap(item -> item.getClassNames().stream())
                .collect(Collectors.toSet());
        assertTrue(serializableClasses.containsAll(Set.of(ArrayList.class.getName(), String.class.getName(), "java.time.Ser")));

        context.produce(new FeatureBuildItem("serializable-result-types-test"));
    }

    @Test
    public void test() {
        // Assertions are performed at build time
    }

    @Mapper
    @CacheNamespace
    public interface CachedMapper {
        List<CachedResult> findAll();

        Cursor<CachedResult> cursor();
    }

    public static class CachedResult {
        public NestedResult nested;
    }

    public static class NestedResult {
        public String value;
    }

    @Mapper
    public interface XmlCachedMapper {
        Optional<XmlCachedResult> findById(Integer id);
    }

    public static class XmlCachedResult {
        public Integer id;
    }

    @Mapper
    @CacheNamespace(readWrite = false)
    public interface ReadOnlyCachedMapper {
        ReadOnlyResult findById(Integer id);
    }

    public static class ReadOnlyResult {
        public Integer id;
    }

    @Mapper
    public interface UncachedMapper {
        UncachedResult[] findAll();
    }

    public static class UncachedResult {
        public Integer id;
    }
}
