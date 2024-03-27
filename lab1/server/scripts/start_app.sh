#!/bin/bash

# Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
# 
# Permission is hereby granted, free of charge, to any person obtaining a copy of this
# software and associated documentation files (the "Software"), to deal in the Software
# without restriction, including without limitation the rights to use, copy, modify,
# merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
# permit persons to whom the Software is furnished to do so.
# 
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
# INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
# PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
# HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
# OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
# SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

cd /home/ec2-user

IMDSv2=$(curl -s -H "X-aws-ec2-metadata-token-ttl-seconds: 60" -X PUT http://169.254.169.254/latest/api/token)
export AWS_REGION=$(curl -s -H "X-aws-ec2-metadata-token: $IMDSv2" http://169.254.169.254/latest/meta-data/placement/region)
export AWS_DEFAULT_REGION=$AWS_REGION

export DB_HOST=$(aws ssm get-parameter --name /saas-modernization-workshop/DB_HOST --query 'Parameter.Value' --output text)
export DB_NAME=$(aws ssm get-parameter --name /saas-modernization-workshop/DB_NAME --query 'Parameter.Value' --output text)
export DB_USER=$(aws ssm get-parameter --name /saas-modernization-workshop/DB_USER --query 'Parameter.Value' --output text)
export DB_PASS=$(aws ssm get-parameter --name /saas-modernization-workshop/DB_PASS --query 'Parameter.Value' --output text)

java -jar /home/ec2-user/application.jar > /dev/null 2> /dev/null < /dev/null &
