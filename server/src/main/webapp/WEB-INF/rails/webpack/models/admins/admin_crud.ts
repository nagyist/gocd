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

import {ApiRequestBuilder, ApiResult, ApiVersion} from "helpers/api_request_builder";
import {SparkRoutes} from "helpers/spark_routes";
import {SystemAdmins, SystemAdminsJSON} from "models/admins/types";

export interface BulkUpdateSystemAdminJSON {
  operations: {
    users: {
      add?: string[],
      remove?: string[]
    }
  };
}

export class AdminsCRUD {
  static API_VERSION_HEADER = ApiVersion.v2;

  static all() {
    return ApiRequestBuilder.GET(SparkRoutes.apisystemAdminsPath(), this.API_VERSION_HEADER)
                            .then(this.extractBody());
  }

  static bulkUpdate(bulkUpdateSystemAdminJson: BulkUpdateSystemAdminJSON) {
    return ApiRequestBuilder.PATCH(SparkRoutes.apisystemAdminsPath(),
                                   this.API_VERSION_HEADER,
                                   {payload: bulkUpdateSystemAdminJson})
                            .then(this.extractBody());
  }

  private static extractBody() {
    return (result: ApiResult<string>) => result.map((body) => {
      return SystemAdmins.fromJSON(JSON.parse(body) as SystemAdminsJSON);
    });
  }
}
