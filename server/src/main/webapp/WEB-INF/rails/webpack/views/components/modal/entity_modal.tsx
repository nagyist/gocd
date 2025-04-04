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
import classNames from "classnames/bind";
import {ApiResult, ErrorResponse, ObjectWithEtag, SuccessResponse} from "helpers/api_request_builder";
import _ from "lodash";
import m from "mithril";
import Stream from "mithril/stream";
import {ValidatableMixin} from "models/mixins/new_validatable_mixin";
import {PluginInfo, PluginInfos} from "models/shared/plugin_infos_new/plugin_info";
import * as Buttons from "views/components/buttons";
import {ButtonGroup} from "views/components/buttons";
import {FlashMessage, FlashMessageModel} from "views/components/flash_message";
import {InfoCircle} from "views/components/icons";
import * as foundationStyles from "views/pages/new_plugins/foundation_hax.scss";
import {OperationState} from "views/pages/page_operations";
import {Modal, ModalState, Size} from "./index";
import styles from "./index.scss";

const foundationClassNames = classNames.bind(foundationStyles);

export abstract class EntityModal<T extends ValidatableMixin> extends Modal {
  protected entity: Stream<T>;
  protected readonly pluginInfos: PluginInfos;
  protected readonly flashMessage: FlashMessageModel;
  protected readonly onSuccessfulSave: (msg: m.Children) => any;
  protected saveFailureIdentifier: m.Children;
  protected readonly isStale              = Stream(true);
  protected readonly etag: Stream<string> = Stream();
  protected ajaxOperationMonitor          = Stream<OperationState>(OperationState.UNKNOWN);
  protected needsFoundationStyles         = Stream(true);

  constructor(entity: T,
              pluginInfos: PluginInfos,
              onSuccessfulSave: (msg: m.Children) => any,
              size: Size = Size.large) {
    super(size);
    this.entity           = Stream(entity);
    this.pluginInfos      = pluginInfos;
    this.onSuccessfulSave = onSuccessfulSave;
    this.flashMessage     = new FlashMessageModel();
  }

  render(): void {
    super.render();

    if (this.isStale()) {
      this.modalState = ModalState.LOADING;
      this.performFetch(this.entity()).then(this.onFetchResult.bind(this));
    }
  }

  performOperation() {
    if (!this.entity().isValid()) {
      return Promise.resolve();
    }

    this.modalState            = ModalState.LOADING;
    this.saveFailureIdentifier = undefined;
    return this.operationPromise().then(this.onSaveResult.bind(this));
  }

  body(): m.Children {
    let msg: m.Children;
    if (this.flashMessage.hasMessage()) {
      msg = <div class={styles.errorWrapper}>
        <FlashMessage type={this.flashMessage.type} message={this.flashMessage.message}/>
      </div>;
    }

    return [
      msg,
      <div className={foundationClassNames({
                                             [foundationStyles.foundationGridHax]: this.needsFoundationStyles(),
                                             [foundationStyles.foundationFormHax]: this.needsFoundationStyles()
                                           })}>
        {this.modalBody()}
      </div>
    ];
  }

  buttons() {
    return [
      <ButtonGroup>
        <Buttons.Cancel data-test-id="button-cancel" onclick={(e: MouseEvent) => this.close()}
                        ajaxOperationMonitor={this.ajaxOperationMonitor}>Cancel</Buttons.Cancel>
        <Buttons.Primary data-test-id="button-save"
                         disabled={this.isLoading()}
                         ajaxOperationMonitor={this.ajaxOperationMonitor}
                         ajaxOperation={this.performOperation.bind(this)}>Save</Buttons.Primary>
        {this.saveFailureIdentifier}
      </ButtonGroup>
    ];
  }

  fetchCompleted() {
    //implement if needed
  }

  operationError(errorResponse: any, statusCode: number) {
    //implement if needed
  }

  protected isLoading(): boolean {
    return super.isLoading() || !this.entity;
  }

  protected abstract onPluginChange(entity: Stream<T>, pluginInfo: PluginInfo): void;

  protected abstract operationPromise(): Promise<any>;

  protected abstract successMessage(): m.Children;

  protected abstract performFetch(entity: T): Promise<any>;

  protected abstract modalBody(): m.Children;

  protected abstract parseJsonToEntity(json: object): T;

  protected pluginIdProxy(newPluginId?: string): any {
    if (!newPluginId) {
      //@ts-ignore
      return this.entity().pluginId();
    }

    //@ts-ignore
    if (newPluginId !== this.entity().pluginId()) {
      const pluginInfo = _.find(this.pluginInfos, (pluginInfo) => pluginInfo.id === newPluginId) as PluginInfo;
      this.onPluginChange(this.entity, pluginInfo);
    }

    return newPluginId;
  }

  protected afterSuccess(): void {
    //implement if needed
  }

  protected errorMessage(msg: string) {
    this.flashMessage.alert(msg);
  }

  private onFetchResult(result: ApiResult<ObjectWithEtag<T>>) {
    this.modalState = ModalState.OK;
    result.do(this.onSuccessfulFetch.bind(this), (e) => this.onError.bind(this, e, result.getStatusCode()));
  }

  private onSuccessfulFetch(successResponse: SuccessResponse<ObjectWithEtag<T>>) {
    this.entity(successResponse.body.object);
    this.etag(successResponse.body.etag);
    this.isStale(false);
    this.fetchCompleted();
  }

  private onSaveResult(result: ApiResult<ObjectWithEtag<T>>) {
    this.modalState = ModalState.OK;
    result.do(this.onSuccess.bind(this),
              this.onError.bind(this, result.unwrap() as ErrorResponse, result.getStatusCode()));
  }

  private onSuccess(successResponse: SuccessResponse<ObjectWithEtag<T>>) {
    this.onSuccessfulSave(this.successMessage());
    this.entity(successResponse.body.object);
    this.afterSuccess();
    this.close();
  }

  private onError(errorResponse: ErrorResponse, statusCode: number) {
    if (422 === statusCode) {
      if (errorResponse.data) {
        this.entity(this.parseJsonToEntity(errorResponse.data));
      } else if (errorResponse.body) {
        this.flashMessage.alert(JSON.parse(errorResponse.body!).message);
      }
    }
    this.operationError(errorResponse, statusCode);
    this.saveFailureIdentifier = <div className={styles.warning}><InfoCircle iconOnly={true} data-test-id="update-failure"/></div>;
  }
}

export abstract class EntityModalWithCheckConnection<T extends ValidatableMixin> extends EntityModal<T> {

  performCheckConnection() {
    if (!this.entity().isValid()) {
      return Promise.reject();
    }
    return this.verifyConnectionOperationPromise().then(this.onVerifyConnectionResult.bind(this));
  }

  getFlashMessage() {
    return this.flashMessage;
  }

  buttons() {
    return [
      <ButtonGroup>
        <Buttons.Primary data-test-id="button-check-connection"
                         ajaxOperationMonitor={this.ajaxOperationMonitor}
                         ajaxOperation={this.performCheckConnection.bind(this)}>Check connection</Buttons.Primary>
        <Buttons.Primary data-test-id="button-save"
                         disabled={this.isLoading()}
                         ajaxOperationMonitor={this.ajaxOperationMonitor}
                         ajaxOperation={this.performOperation.bind(this)}>Save</Buttons.Primary>
        {this.saveFailureIdentifier}
      </ButtonGroup>
    ];
  }

  protected abstract verifyConnectionOperationPromise(): Promise<any>;

  private onVerifyConnectionResult(result: ApiResult<any>) {
    return result.do(this.onVerifyConnectionSuccess.bind(this), this.onVerifyConnectionError.bind(this));
  }

  private onVerifyConnectionSuccess(successResponse: any) {
    this.flashMessage.success(successResponse.body.message);
  }

  private onVerifyConnectionError(errorResponse: ErrorResponse) {
    this.flashMessage.alert(errorResponse.body ? JSON.parse(errorResponse.body).message : errorResponse.message);
  }
}
