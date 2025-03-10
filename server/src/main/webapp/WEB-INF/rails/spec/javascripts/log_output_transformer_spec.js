/*
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
 */
(function ($) {
  "use strict";

  describe("LogOutputTransformerSpec", function LogOutputTransformerSpec() {
    var transformer, output, fixture;

    function extractText(collection) {
      return _.map(collection, function (el) {
        return $(el).text();
      });
    }

    function extractAttr(collection, name) {
      return _.map(collection, function (el) {
        return $(el).attr(name);
      });
    }


    beforeEach(function () {
      window.requestAnimationFrame = function (callback) {
        callback();
      };
      setFixtures(`
        <div id='fixture'>
          <div class='console-log-loading'></div>
          <div id="console"></div>
        </div>
      `);
      fixture = $('#fixture');
      transformer = new LogOutputTransformer(output = $("#console"), FoldableSection, false);
    });

    afterEach(function () {
      delete window.requestAnimationFrame;
    });

    it("basic unprefixed append to console", function () {
      var lines = [
        "Starting build",
        "Build finished in no time!"
      ];

      transformer.transform(lines);
      expect(output.find("dd").length).toBe(2);

      var actual = extractText(output.find("dd"));
      expect(actual.join("\n")).toBe(lines.join("\n")); // can't assertEquals() on arrays, so compare as strings
    });

    it("basic prefixed append to console", function () {
      var lines = [
        "##|01:01:00.123 Starting build",
        "##|01:02:00.123 Build finished in no time!"
      ];

      transformer.transform(lines);
      var section = output.find(".log-fs-type-info");
      expect(!!section.length).toBe(true);

      var timestamps = extractAttr(section.find(".log-fs-line"), "data-timestamp").join(",");
      expect(timestamps).toBe(["01:01:00.123", "01:02:00.123"].join(","));

      output.find(".ts").remove(); // exclude timestamps so it's easier to assert content
      var actual = extractText(output.find(".log-fs-line-INFO"));
      expect(actual.join("\n")).toBe(["Starting build", "Build finished in no time!"].join("\n")); // can't assertEquals() on arrays, so compare as strings
    });

    it("should remove loading bar when console has lines to show", function () {
      var lines = [
        "##|01:01:00.123 Starting build",
        "##|01:02:00.123 Build finished in no time!"
      ];

      expect(fixture.find(".console-log-loading").is(':visible')).toBe(true);
      transformer.transform(lines);

      expect(fixture.find(".console-log-loading").is(':visible')).toBe(false);
    });

    it("should remove loading bar when build has finished and there is no output", function () {
      expect(fixture.find(".console-log-loading").is(':visible')).toBe(true);
      output.trigger('consoleCompleted');

      expect(fixture.find(".console-log-loading").is(':visible')).toBe(false);
    });

    it("should inline multiline executable task", function () {
      var lines = [
        "!!|12:33:01.817 [go] Task: /bin/bash -c \"echo -e 'this is \n",
        "!!|12:33:01.817 theBestUse \n",
        "!!|12:33:01.817 ofOurplugin\n",
        "!!|12:33:01.817 '>testFile.txt\"\n"
      ];

      var expectedLines = [
        "!!|12:33:01.817 [go] Task: /bin/bash -c \"echo -e 'this is \\ntheBestUse \\nofOurplugin\\n'>testFile.txt\""
      ];

      transformer.transform(lines);

      expect(expectedLines[0]).toBe(lines[0]);
    });
  });

})(jQuery);
