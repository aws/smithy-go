package software.amazon.smithy.go.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DocumentationConverterTest {
    private static final int DEFAULT_DOC_WRAP_LENGTH = 80;

    @ParameterizedTest
    @MethodSource("cases")
    void convertsDocs(String given, String expected) {
        assertThat(DocumentationConverter.convert(given, DEFAULT_DOC_WRAP_LENGTH), equalTo(expected));
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
//                Arguments.of("<ul>\n            <li>\n               <p>see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/acl-overview.html#CannedACL\">Canned ACL</a>.</p>\n            </li>\n            </ul>",
//                                        "Test"
//                        ),
//                Arguments.of("<p>Creates a new S3 bucket. To create a bucket, you must register with Amazon S3 and have a\n         valid Amazon Web Services Access Key ID to authenticate requests. Anonymous requests are never allowed to\n         create buckets. By creating the bucket, you become the bucket owner.</p>\n         <p>Not every string is an acceptable bucket name. For information about bucket naming\n         restrictions, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html\">Bucket naming rules</a>.</p>\n         <p>If you want to create an Amazon S3 on Outposts bucket, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_control_CreateBucket.html\">Create Bucket</a>. </p>\n         <p>By default, the bucket is created in the US East (N. Virginia) Region. You can\n         optionally specify a Region in the request body. You might choose a Region to optimize\n         latency, minimize costs, or address regulatory requirements. For example, if you reside in\n         Europe, you will probably find it advantageous to create buckets in the Europe (Ireland)\n         Region. For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/UsingBucket.html#access-bucket-intro\">Accessing a\n         bucket</a>.</p>\n         <note>\n            <p>If you send your create bucket request to the <code>s3.amazonaws.com</code> endpoint,\n            the request goes to the us-east-1 Region. Accordingly, the signature calculations in\n            Signature Version 4 must use us-east-1 as the Region, even if the location constraint in\n            the request specifies another Region where the bucket is to be created. If you create a\n            bucket in a Region other than US East (N. Virginia), your application must be able to\n            handle 307 redirect. For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/VirtualHosting.html\">Virtual hosting of buckets</a>.</p>\n         </note>\n         <p>\n            <b>Access control lists (ACLs)</b>\n         </p>\n         <p>When creating a bucket using this operation, you can optionally configure the bucket ACL to specify the accounts or\n         groups that should be granted specific permissions on the bucket.</p>\n         <important>\n            <p>If your CreateBucket request sets bucket owner enforced for S3 Object Ownership and\n            specifies a bucket ACL that provides access to an external Amazon Web Services account, your request\n            fails with a <code>400</code> error and returns the\n               <code>InvalidBucketAclWithObjectOwnership</code> error code. For more information,\n            see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/about-object-ownership.html\">Controlling object\n               ownership</a> in the <i>Amazon S3 User Guide</i>.</p>\n         </important>\n         <p>There are two ways to grant the appropriate permissions using the request headers.</p>\n         <ul>\n            <li>\n               <p>Specify a canned ACL using the <code>x-amz-acl</code> request header. Amazon S3\n               supports a set of predefined ACLs, known as <i>canned ACLs</i>. Each\n               canned ACL has a predefined set of grantees and permissions. For more information,\n               see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/acl-overview.html#CannedACL\">Canned ACL</a>.</p>\n            </li>\n            <li>\n               <p>Specify access permissions explicitly using the <code>x-amz-grant-read</code>,\n                  <code>x-amz-grant-write</code>, <code>x-amz-grant-read-acp</code>,\n                  <code>x-amz-grant-write-acp</code>, and <code>x-amz-grant-full-control</code>\n               headers. These headers map to the set of permissions Amazon S3 supports in an ACL. For\n               more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/acl-overview.html\">Access control list\n                  (ACL) overview</a>.</p>\n               <p>You specify each grantee as a type=value pair, where the type is one of the\n               following:</p>\n               <ul>\n                  <li>\n                     <p>\n                        <code>id</code> – if the value specified is the canonical user ID of an Amazon Web Services account</p>\n                  </li>\n                  <li>\n                     <p>\n                        <code>uri</code> – if you are granting permissions to a predefined\n                     group</p>\n                  </li>\n                  <li>\n                     <p>\n                        <code>emailAddress</code> – if the value specified is the email address of\n                     an Amazon Web Services account</p>\n                     <note>\n                        <p>Using email addresses to specify a grantee is only supported in the following Amazon Web Services Regions: </p>\n                        <ul>\n                           <li>\n                              <p>US East (N. Virginia)</p>\n                           </li>\n                           <li>\n                              <p>US West (N. California)</p>\n                           </li>\n                           <li>\n                              <p> US West (Oregon)</p>\n                           </li>\n                           <li>\n                              <p> Asia Pacific (Singapore)</p>\n                           </li>\n                           <li>\n                              <p>Asia Pacific (Sydney)</p>\n                           </li>\n                           <li>\n                              <p>Asia Pacific (Tokyo)</p>\n                           </li>\n                           <li>\n                              <p>Europe (Ireland)</p>\n                           </li>\n                           <li>\n                              <p>South America (São Paulo)</p>\n                           </li>\n                        </ul>\n                        <p>For a list of all the Amazon S3 supported Regions and endpoints, see <a href=\"https://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region\">Regions and Endpoints</a> in the Amazon Web Services General Reference.</p>\n                     </note>\n                  </li>\n               </ul>\n               <p>For example, the following <code>x-amz-grant-read</code> header grants the Amazon Web Services accounts identified by account IDs permissions to read object data and its metadata:</p>\n               <p>\n                  <code>x-amz-grant-read: id=\"11112222333\", id=\"444455556666\" </code>\n               </p>\n            </li>\n         </ul>\n         <note>\n            <p>You can use either a canned ACL or specify access permissions explicitly. You cannot\n            do both.</p>\n         </note>\n         <p>\n            <b>Permissions</b>\n         </p>\n         <p>In addition to <code>s3:CreateBucket</code>, the following permissions are required when your CreateBucket includes specific headers:</p>\n         <ul>\n            <li>\n               <p>\n                  <b>ACLs</b> - If your <code>CreateBucket</code> request specifies ACL permissions and the ACL is public-read, public-read-write, \n               authenticated-read, or if you specify access permissions explicitly through any other ACL, both \n               <code>s3:CreateBucket</code> and <code>s3:PutBucketAcl</code> permissions are needed. If the ACL the \n               <code>CreateBucket</code> request is private or doesn't specify any ACLs, only <code>s3:CreateBucket</code> permission is needed. </p>\n            </li>\n            <li>\n               <p>\n                  <b>Object Lock</b> - If\n                  <code>ObjectLockEnabledForBucket</code> is set to true in your\n                  <code>CreateBucket</code> request,\n                  <code>s3:PutBucketObjectLockConfiguration</code> and\n                  <code>s3:PutBucketVersioning</code> permissions are required.</p>\n            </li>\n            <li>\n               <p>\n                  <b>S3 Object Ownership</b> - If your CreateBucket\n               request includes the the <code>x-amz-object-ownership</code> header,\n                  <code>s3:PutBucketOwnershipControls</code> permission is required.</p>\n            </li>\n         </ul>\n         <p>The following operations are related to <code>CreateBucket</code>:</p>\n         <ul>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html\">PutObject</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucket.html\">DeleteBucket</a>\n               </p>\n            </li>\n         </ul>",
//                                        "Test"
//                        ),
//                Arguments.of("<ul>\n                  <li>\n                     <p>Description: One or more of the specified parts could not be found. The part\n                     might not have been uploaded, or the specified entity tag might not have\n                     matched the part's entity tag.</p>\n                  </li>                  </ul>",
//                                        "test"
//                        ),
//                Arguments.of( "<p>Completes a multipart upload by assembling previously uploaded parts.</p>\n         <p>You first initiate the multipart upload and then upload all parts using the <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html\">UploadPart</a>\n         operation. After successfully uploading all relevant parts of an upload, you call this\n         action to complete the upload. Upon receiving this request, Amazon S3 concatenates all\n         the parts in ascending order by part number to create a new object. In the Complete\n         Multipart Upload request, you must provide the parts list. You must ensure that the parts\n         list is complete. This action concatenates the parts that you provide in the list. For\n         each part in the list, you must provide the part number and the <code>ETag</code> value,\n         returned after that part was uploaded.</p>\n         <p>Processing of a Complete Multipart Upload request could take several minutes to\n         complete. After Amazon S3 begins processing the request, it sends an HTTP response header that\n         specifies a 200 OK response. While processing is in progress, Amazon S3 periodically sends white\n         space characters to keep the connection from timing out. Because a request could fail after\n         the initial 200 OK response has been sent, it is important that you check the response body\n         to determine whether the request succeeded.</p>\n         <p>Note that if <code>CompleteMultipartUpload</code> fails, applications should be prepared\n         to retry the failed requests. For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/ErrorBestPractices.html\">Amazon S3 Error Best Practices</a>.</p>\n         <important>\n            <p>You cannot use <code>Content-Type: application/x-www-form-urlencoded</code> with Complete\n            Multipart Upload requests. Also, if you do not provide a <code>Content-Type</code> header, <code>CompleteMultipartUpload</code> returns a 200 OK response.</p>\n         </important>\n         <p>For more information about multipart uploads, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/uploadobjusingmpu.html\">Uploading Objects Using Multipart\n            Upload</a>.</p>\n         <p>For information about permissions required to use the multipart upload API, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/mpuAndPermissions.html\">Multipart Upload and\n         Permissions</a>.</p>\n         <p>\n            <code>CompleteMultipartUpload</code> has the following special errors:</p>\n         <ul>\n            <li>\n               <p>Error code: <code>EntityTooSmall</code>\n               </p>\n               <ul>\n                  <li>\n                     <p>Description: Your proposed upload is smaller than the minimum allowed object\n                     size. Each part must be at least 5 MB in size, except the last part.</p>\n                  </li>\n                  <li>\n                     <p>400 Bad Request</p>\n                  </li>\n               </ul>\n            </li>\n            <li>\n               <p>Error code: <code>InvalidPart</code>\n               </p>\n               <ul>\n                  <li>\n                     <p>Description: One or more of the specified parts could not be found. The part\n                     might not have been uploaded, or the specified entity tag might not have\n                     matched the part's entity tag.</p>\n                  </li>\n                  <li>\n                     <p>400 Bad Request</p>\n                  </li>\n               </ul>\n            </li>\n            <li>\n               <p>Error code: <code>InvalidPartOrder</code>\n               </p>\n               <ul>\n                  <li>\n                     <p>Description: The list of parts was not in ascending order. The parts list\n                     must be specified in order by part number.</p>\n                  </li>\n                  <li>\n                     <p>400 Bad Request</p>\n                  </li>\n               </ul>\n            </li>\n            <li>\n               <p>Error code: <code>NoSuchUpload</code>\n               </p>\n               <ul>\n                  <li>\n                     <p>Description: The specified multipart upload does not exist. The upload ID\n                     might be invalid, or the multipart upload might have been aborted or\n                     completed.</p>\n                  </li>\n                  <li>\n                     <p>404 Not Found</p>\n                  </li>\n               </ul>\n            </li>\n         </ul>\n         <p>The following operations are related to <code>CompleteMultipartUpload</code>:</p>\n         <ul>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html\">CreateMultipartUpload</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html\">UploadPart</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_AbortMultipartUpload.html\">AbortMultipartUpload</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListParts.html\">ListParts</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListMultipartUploads.html\">ListMultipartUploads</a>\n               </p>\n            </li>\n         </ul>",
//                                        "test"
//                        ),
//                Arguments.of("<p>Amazon S3 default encryption feature, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/bucket-encryption.html\">Amazon S3 Default Bucket Encryption</a>.</p>\n         <p> To use this operation, you must have permission to perform the\n            <code>s3:GetEncryptionConfiguration</code> action. The bucket owner has this permission\n         by default. The bucket owner can grant this permission to others. For more information\n         about permissions, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-actions.html#using-with-s3-actions-related-to-bucket-subresources\">Permissions Related to Bucket Subresource Operations</a> and <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-access-control.html\">Managing Access Permissions to Your Amazon S3\n            Resources</a>.</p>\n         <p>The following operations are related to <code>GetBucketEncryption</code>:</p>\n         <ul>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketEncryption.html\">PutBucketEncryption</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucketEncryption.html\">DeleteBucketEncryption</a>\n               </p>\n            </li>\n         </ul>",
//                                        "Test"
//                        ),
//                Arguments.of("<p>This action aborts a multipart upload. After a multipart upload is aborted, no\n         additional parts can be uploaded using that upload ID. The storage consumed by any\n         previously uploaded parts will be freed. However, if any part uploads are currently in\n         progress, those part uploads might or might not succeed. As a result, it might be necessary\n         to abort a given multipart upload multiple times in order to completely free all storage\n         consumed by all parts. </p>\n         <p>To verify that all parts have been removed, so you don't get charged for the part\n         storage, you should call the <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListParts.html\">ListParts</a> action and ensure that\n         the parts list is empty.</p>\n         <p>For information about permissions required to use the multipart upload, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/mpuAndPermissions.html\">Multipart Upload and\n         Permissions</a>.</p>\n         <p>The following operations are related to <code>AbortMultipartUpload</code>:</p>\n         <ul>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html\">CreateMultipartUpload</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html\">UploadPart</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html\">CompleteMultipartUpload</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListParts.html\">ListParts</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListMultipartUploads.html\">ListMultipartUploads</a>\n               </p>\n            </li>\n         </ul>",
//                            "Test"
//                ),
//                Arguments.of("<p>The following operations are related to <code>AbortMultipartUpload</code>:</p>\n         <ul>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html\">CreateMultipartUpload</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html\">UploadPart</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html\">CompleteMultipartUpload</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListParts.html\">ListParts</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListMultipartUploads.html\">ListMultipartUploads</a>\n               </p>\n            </li>\n         </ul>",
//                            "Test"
//                        )
                Arguments.of(
                        "Testing 1 2 3",
                        "Testing 1 2 3"
                ),
                Arguments.of(
                        "<a href=\"https://example.com\">a link</a>",
                        "a link (https://example.com)"
                ),
                Arguments.of(
                        "<a href=\" https://example.com\">a link</a>",
                        "a link (https://example.com)"
                ),
                Arguments.of(
                        "<a>empty link</a>",
                        "empty link"
                ),
                Arguments.of(
                        "<ul><li>Testing 1 2 3</li> <li>FooBar</li></ul>",
                        "    - Testing 1 2 3\n    - FooBar"
                ),
                Arguments.of(
                        "<ul> <li>Testing 1 2 3</li> <li>FooBar</li> </ul>",
                        "    - Testing 1 2 3\n    - FooBar"
                ),
                Arguments.of(
                        " <ul> <li>Testing 1 2 3</li> <li>FooBar</li> </ul>",
                        "    - Testing 1 2 3\n    - FooBar"
                ),
                Arguments.of(
                        "<ul> <li> <p>Testing 1 2 3</p> </li><li> <p>FooBar</p></li></ul>",
                        "    - Testing 1 2 3\n    - FooBar"
                ),
                Arguments.of(
                        "<ul> <li><code>Testing</code>: 1 2 3</li> <li>FooBar</li> </ul>",
                        "    - Testing : 1 2 3\n    - FooBar"
                ),
                Arguments.of(
                        "<ul> <li><p><code>FOO</code> Bar</p></li><li><p><code>Xyz</code> ABC</p></li></ul>",
                        "    - FOO Bar\n    - Xyz ABC"
                ),
                Arguments.of(
                        "<ul><li>        foo</li><li>\tbar</li><li>\nbaz</li></ul>",
                        "    - foo\n    - bar\n    - baz"
                ),
                Arguments.of(
                        "<p><code>Testing</code>: 1 2 3</p>",
                        "Testing : 1 2 3"
                ),
                Arguments.of(
                        "<pre><code>Testing</code></pre>",
                        "    Testing"
                ),
                Arguments.of(
                        "<p>Testing 1 2                       3</p>",
                        "Testing 1 2 3"
                ),
                Arguments.of(
                        "<span data-target-type=\"operation\" data-service=\"secretsmanager\" "
                                + "data-target=\"CreateSecret\">CreateSecret</span> <span data-target-type="
                                + "\"structure\" data-service=\"secretsmanager\" data-target=\"SecretListEntry\">"
                                + "SecretListEntry</span> <span data-target-type=\"structure\" data-service="
                                + "\"secretsmanager\" data-target=\"CreateSecret$SecretName\">SecretName</span> "
                                + "<span data-target-type=\"structure\" data-service=\"secretsmanager\" "
                                + "data-target=\"SecretListEntry$KmsKeyId\">KmsKeyId</span>",
                        "CreateSecret SecretListEntry SecretName KmsKeyId"
                ),
                Arguments.of(
                        "<p> Deletes the replication configuration from the bucket. For information about replication"
                                + " configuration, see "
                                + "<a href=\" https://docs.aws.amazon.com/AmazonS3/latest/dev/crr.html\">"
                                + "Cross-Region Replication (CRR)</a> in the <i>Amazon S3 Developer Guide</i>. </p>",
                        "Deletes the replication configuration from the bucket. For information about\n" +
                                "replication configuration, see Cross-Region Replication (CRR) (https://docs.aws.amazon.com/AmazonS3/latest/dev/crr.html)\n" +
                                "in the Amazon S3 Developer Guide."
                ),
                Arguments.of(
                        "* foo\n* bar",
                        "    - foo\n    - bar"
                ),
                Arguments.of(
                        "[a link](https://example.com)",
                        "a link (https://example.com)"
                ),
                Arguments.of("", ""),
                Arguments.of("<!-- foo bar -->", ""),
                Arguments.of("# Foo\nbar", "Foo\nbar"),
                Arguments.of("<h1>Foo</h1>bar", "Foo\nbar"),
                Arguments.of("## Foo\nbar", "Foo\nbar"),
                Arguments.of("<h2>Foo</h2>bar", "Foo\nbar"),
                Arguments.of("### Foo\nbar", "Foo\nbar"),
                Arguments.of("<h3>Foo</h3>bar", "Foo\nbar"),
                Arguments.of("#### Foo\nbar", "Foo\nbar"),
                Arguments.of("<h4>Foo</h4>bar", "Foo\nbar"),
                Arguments.of("##### Foo\nbar", "Foo\nbar"),
                Arguments.of("<h5>Foo</h5>bar", "Foo\nbar"),
                Arguments.of("###### Foo\nbar", "Foo\nbar"),
                Arguments.of("<h6>Foo</h6>bar", "Foo\nbar"),
                Arguments.of("Inline `code`", "Inline code"),
                Arguments.of("```\ncode block\n ```", "    code block"),
                Arguments.of("```java\ncode block\n ```", "    code block"),
                Arguments.of("foo<br/>bar", "foo\n\nbar"),
                Arguments.of("         <p>foo</p>", "foo")
        );
    }
}
