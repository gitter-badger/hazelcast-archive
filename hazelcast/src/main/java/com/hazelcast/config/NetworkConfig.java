/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.config;

public class NetworkConfig {
    private Interfaces interfaces = new Interfaces();

    private Join join = new Join();

    private SymmetricEncryptionConfig symmetricEncryptionConfig = null;

    private AsymmetricEncryptionConfig asymmetricEncryptionConfig = null;

    /**
     * @return the interfaces
     */
    public Interfaces getInterfaces() {
        return interfaces;
    }

    /**
     * @param interfaces the interfaces to set
     */
    public NetworkConfig setInterfaces(final Interfaces interfaces) {
        this.interfaces = interfaces;
        return this;
    }

    /**
     * @return the join
     */
    public Join getJoin() {
        return join;
    }

    /**
     * @param join the join to set
     */
    public NetworkConfig setJoin(final Join join) {
        this.join = join;
        return this;
    }

    public SymmetricEncryptionConfig getSymmetricEncryptionConfig() {
        return symmetricEncryptionConfig;
    }

    public NetworkConfig setSymmetricEncryptionConfig(final SymmetricEncryptionConfig symmetricEncryptionConfig) {
        this.symmetricEncryptionConfig = symmetricEncryptionConfig;
        return this;
    }

    public AsymmetricEncryptionConfig getAsymmetricEncryptionConfig() {
        return asymmetricEncryptionConfig;
    }

    public NetworkConfig setAsymmetricEncryptionConfig(final AsymmetricEncryptionConfig asymmetricEncryptionConfig) {
        this.asymmetricEncryptionConfig = asymmetricEncryptionConfig;
        return this;
    }
}
