/*
 * Copyright 2018 ThoughtWorks, Inc.
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

const _      = require("lodash");
const Stream = require("mithril/stream");

const FilterValidations = require("views/dashboard/models/filter_validations");
const PipelineListVM    = require("views/dashboard/models/pipeline_list_vm");

function PersonalizeEditorVM(opts) { // opts is usually the current filter
  normalize(opts);

  FilterValidations.call(this, opts);

  const name = Stream(opts.name);
  const type = Stream(opts.type);
  const state = Stream(opts.state);
  const selectionVM = Stream();

  const inverted = () => "blacklist" === type();

  this.name = name;
  this.selectionVM = selectionVM;

  this.onLoadPipelines = (pipelinesByGroup) => {
    selectionVM(PipelineListVM.create(pipelinesByGroup, inverted(), opts.pipelines));
  };

  boolToList(this, state, "building");
  boolToList(this, state, "failing");

  this.includeNewPipelines = function (value) {
    if (!arguments.length) { return inverted(); }
    type(value ? "blacklist" : "whitelist");
  };

  this.errorResponse = Stream();

  this.asFilter = () => {
    const pipelines = selectionVM() ? [] : selectionVM().pipelines(inverted());
    return {name: name(), type: type(), pipelines, state: state()};
  };
}

function normalize(opts) {
  opts.name = opts.name || "";
  opts.type = opts.type || "blacklist";
  opts.pipelines = arr(opts.pipelines);
  opts.state = arr(opts.state);
}

function arr(val) { // ensure value is an array
  if ("undefined" === typeof val) { return []; }
  if (val instanceof Array) { return val; }
  return [val];
}

// allows a boolean to control the presence of a value in
// a list within a m.Stream
function boolToList(model, stream, attr) {
  model[attr] = function(value) {
    const r = stream();
    if (!arguments.length) { return _.includes(r, attr); }

    if (value) {
      r.push(attr); // toggle to `true` => add to list
    } else {
      r.splice(r.indexOf(attr), 1); // toggle to `false` => remove from list
    }
    stream(r.sort());
  };
}

module.exports = PersonalizeEditorVM;
