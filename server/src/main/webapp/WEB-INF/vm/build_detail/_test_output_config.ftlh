<#--
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 -->
<#assign tab='&nbsp;&nbsp;&nbsp;'>
<p>No test output found. Make sure your job configuration includes a test artifact.</p><br/>
<p>For example:</p><br/>
<pre>
<#noautoesc>
&lt;job name="example"&gt;
${tab}&lt;resources&gt;
${tab}${tab}&lt;resource&gt;java&lt;/resource&gt;
${tab}&lt;/resources&gt;
<strong class='code'>${tab}&lt;artifacts&gt;
${tab}${tab}&lt;test src="target/reports" /&gt;
${tab}&lt;/artifacts&gt;</strong>
${tab}&lt;tasks&gt;
${tab}${tab}&lt;ant target="unit_tests" /&gt;
${tab}&lt;/tasks&gt;
&lt;/job&gt;
</#noautoesc>
</pre>
<br/>
<p><a href="${currentGoCDVersion.docsUrl('/configuration/managing_artifacts_and_reports.html')}" target='_blank' class="obvious_link">Help Topic: Managing Artifacts and Reports</a></p>
