package org.infinispan.lucene;

import java.util.Arrays;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Tests basic functionality of LuceneKey2StringMapper
 * @see LuceneKey2StringMapper
 *
 * @author Sanne Grinovero
 * @since 4.1
 */
@Test(groups = "functional", testName = "lucene.Key2StringMapperTest")
public class Key2StringMapperTest {

   final LuceneKey2StringMapper mapper = new LuceneKey2StringMapper();

   @Test
   public void testRegex() {
      String[] split = LuceneKey2StringMapper.singlePipePattern.split("hello|world");
      AssertJUnit.assertTrue(Arrays.deepEquals(new String[]{"hello", "world"}, split));
   }

   @Test
   public void loadChunkCacheKey() {
      AssertJUnit.assertEquals(new ChunkCacheKey("my addressbook", "sgments0.gen", 34, 16000000, -1), mapper.getKeyMapping("C|sgments0.gen|34|16000000|my addressbook|-1"));
   }

   @Test
   public void loadFileCacheKey() {
      AssertJUnit.assertEquals(new FileCacheKey("poems and songs, 3000AC-2000DC", "filename.extension", -1), mapper.getKeyMapping("M|filename.extension|poems and songs, 3000AC-2000DC|-1"));
   }

   @Test
   public void loadFileListCacheKey() {
      AssertJUnit.assertEquals(new FileListCacheKey("", -1), mapper.getKeyMapping("*||-1"));
      AssertJUnit.assertEquals(new FileListCacheKey("the leaves of Amazonia", -1), mapper.getKeyMapping("*|the leaves of Amazonia|-1"));
   }

   @Test
   public void loadReadLockKey() {
      AssertJUnit.assertEquals(new FileReadLockKey("poems and songs, 3000AC-2000DC", "brushed steel lock", -1), mapper.getKeyMapping("RL|brushed steel lock|poems and songs, 3000AC-2000DC|-1"));
   }

   @Test(expectedExceptions=IllegalArgumentException.class)
   public void failureForIllegalKeys() {
      mapper.getKeyMapping("|*|the leaves of Amazonia");
   }

   @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "Not supporting null keys")
   public void failureForNullKey() {
      mapper.getKeyMapping(null);
   }

   @Test(expectedExceptions = IllegalArgumentException.class)
   public void failureForNotFullKey() {
      mapper.getKeyMapping("sgments0.gen|34");
   }

   @Test(expectedExceptions = IllegalArgumentException.class)
   public void failureForWrongFileCacheKey() {
      mapper.getKeyMapping("filename|M|5|indexname");
   }

   @Test(expectedExceptions = IllegalArgumentException.class)
   public void failureForWrongChunkCacheKey() {
      mapper.getKeyMapping("filename|5a|5|indexname");
   }

   @Test(expectedExceptions = IllegalArgumentException.class)
   public void failureForWrongFileReadLockKey() {
      mapper.getKeyMapping("filename|RL|5|indexname");
   }

   @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "filename must not be null")
   public void testChunkCacheKeyInitWithNllFileName() {
      new ChunkCacheKey("index-A", null, 0, 1024, -1);
   }

   @Test
   public void testChunkCacheKeyComparison() {
      AssertJUnit.assertFalse("The object should not be equals to null.", new ChunkCacheKey("index-A", "fileName", 0, 1024, -1).equals(null));
      AssertJUnit.assertFalse("The ChunkCacheKey objects should not be equal due to different file names.",
                         new ChunkCacheKey("index-A", "fileName", 0, 1024, -1).equals(new ChunkCacheKey("index-A", "fileName1", 0, 1024, -1)));
      AssertJUnit.assertEquals("The ChunkCacheKey objects should be equal.",
                        new ChunkCacheKey("index-A", "fileName", 0, 1024, -1), new ChunkCacheKey("index-A", "fileName", 0, 1024, -1));
   }

   public void testIsSupportedType() {
      assert !mapper.isSupportedType(this.getClass());
      assert mapper.isSupportedType(ChunkCacheKey.class);
      assert mapper.isSupportedType(FileCacheKey.class);
      assert mapper.isSupportedType(FileListCacheKey.class);
      assert mapper.isSupportedType(FileReadLockKey.class);
   }

   @Test(expectedExceptions=IllegalArgumentException.class)
   public void testReadLockKeyIndexNameNull() {
      FileReadLockKey key = new FileReadLockKey(null, "brushed steel lock", -1);
   }

   @Test(expectedExceptions = IllegalArgumentException.class)
   public void testReadLockKeyFileNameNull() {
      FileReadLockKey key = new FileReadLockKey("poems and songs, 3000AC-2000DC", null, -1);
   }

   public void testReadLockEqualsWithNullOrNotEqualObj() {
      FileReadLockKey key = new FileReadLockKey("poems and songs, 3000AC-2000DC", "brushed steel lock", -1);
      AssertJUnit.assertNotNull(key);
      AssertJUnit.assertFalse(new FileReadLockKey("poems and songs, 3000AC-2000DC", "brushed lock", -1)
                     .equals(mapper.getKeyMapping("RL|brushed steel lock|poems and songs, 3000AC-2000DC|-1")));
   }

   @Test
   public void testFileListCacheKeyComparison() {
      AssertJUnit.assertFalse("The instance of FileListCacheKey should not be equal to null.", new FileListCacheKey("index-A", -1).equals(null));
      AssertJUnit.assertFalse("The instance of FileListCacheKey should not be equal to FileCacheKey instance.", new FileListCacheKey("index-A", -1).equals(new FileCacheKey("index-A", "test.txt", -1)));
   }

   @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "filename must not be null")
   public void testFileCacheKeyInit() {
      new FileCacheKey("poems and songs, 3000AC-2000DC", null, -1);
   }

   @Test
   public void testFileCacheKeyCompWithNull() {
      AssertJUnit.assertFalse("The Instance of FileCacheKey should not be equal to null.", new FileCacheKey("poems and songs, 3000AC-2000DC", "fileName.txt", -1).equals(null));
   }
}
