
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.codehaus.plexus.util.*;

try
{
    File target = new File( basedir, "target" )
    if ( !target.exists() || !target.isDirectory() )
    {
        System.err.println( "target file is missing or not a directory." )
        return false
    }

    File primaryArtifact = new File( target, "maven-jlink-plugin-mjlink-52-96.0.jar" )
    if ( !primaryArtifact.exists() || primaryArtifact.isDirectory() )
    {
        System.err.println( "maven-jlink-plugin-mjlink-52-96.0.jar file is missing or is a directory." )
        return false
    }

    File jlinkArtifact = new File( target, "maven-jlink-plugin-mjlink-52-96.0-jlink.zip" )
    if ( !jlinkArtifact.exists() || jlinkArtifact.isDirectory() )
    {
        System.err.println( "maven-jlink-plugin-mjlink-52-96.0-jlink.zip file is missing or is a directory." )
        return false
    }

    return true
}
catch( Throwable e )
{
    e.printStackTrace()
    return false
}
