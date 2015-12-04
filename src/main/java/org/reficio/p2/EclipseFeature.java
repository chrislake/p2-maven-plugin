/**
 * Copyright (c) 2012 Reficio (TM) - Reestablish your software! All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.reficio.p2;

/**
 * @author Tom Bujok (tom.bujok@gmail.com)<br>
 *         Reficio (TM) - Reestablish your software!<br>
 *         http://www.reficio.org
 * @since 1.1.2
 */
public class EclipseFeature {

    /**
     * Feature file name as it appears in the P2 update site.
     * If the feature file is: "org.eclipse.rap.feature.feature.group_2.0.0.20130205-1849" the id is: "org.eclipse.rap.feature.feature.group:2.0.0.20130205-1849".
     */
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

}
