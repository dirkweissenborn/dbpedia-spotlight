/**
 * Copyright 2011 Pablo Mendes, Max Jakob
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
 */

package org.dbpedia.spotlight.exceptions;

/**
 * Created by IntelliJ IDEA.
 * User: PabloMendes
 * Date: Jun 29, 2010
 * Time: 3:48:46 PM
 * To change this template use File | Settings | File Templates.
 */
public class IndexException extends Exception {

    public IndexException(String msg, Exception e) {
        super(msg,e);
    }

    public IndexException(String msg) {
        super(msg);
    }

    public IndexException(Exception e) {
        super(e);
    }

}