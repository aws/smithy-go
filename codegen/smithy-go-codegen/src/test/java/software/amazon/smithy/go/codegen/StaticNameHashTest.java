package software.amazon.smithy.go.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class StaticNameHashTest {
    // These values are shared with TestStaticNameHashGolden in schema_static_test.go of the Go runtime.
    @Test
    public void matchesRuntime() {
        assertEquals(0xa0761d6478bd642fL, StaticNameHash.hash("", 1));
        assertEquals(0xe5a866bb0f16611fL, StaticNameHash.hash("S", 1));
        assertEquals(0x1d9396f36fff6906L, StaticNameHash.hash("NULL", 7));
        assertEquals(0x855c74105557a50cL, StaticNameHash.hash("abc", 3));
        assertEquals(0x2698ed598a319299L, StaticNameHash.hash("BOOL", 0));
        assertEquals(0x66b834422ed040f4L, StaticNameHash.hash("12345678", 5));
        assertEquals(0xc005316efcfc62e9L, StaticNameHash.hash("DataSourceParameters", 12345));
        assertEquals(0x44587801b867f97fL, StaticNameHash.hash("AmazonElasticsearchParameters", 99));
        assertEquals(0x0f121383a229acfaL, StaticNameHash.hash("0123456789abcdef", 42));
        assertEquals(0x2ee0d19534d235d3L, StaticNameHash.hash("0123456789abcdefg", 42));
        assertEquals(0x286ba7241c5f194eL,
                StaticNameHash.hash("The quick brown fox jumps over the lazy dog, 40B", 0xDEADBEEFCAFEF00DL));
    }
}
