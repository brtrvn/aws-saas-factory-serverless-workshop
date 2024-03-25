-- Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
--
-- Permission is hereby granted, free of charge, to any person obtaining a copy of this
-- software and associated documentation files (the "Software"), to deal in the Software
-- without restriction, including without limitation the rights to use, copy, modify,
-- merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
-- permit persons to whom the Software is furnished to do so.
--
-- THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
-- INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
-- PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
-- HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
-- OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
-- SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

-- Load up the UUID data type
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS category (
    tenant_id UUID NOT NULL,
	category_id SERIAL PRIMARY KEY,
	category VARCHAR(255) NOT NULL CHECK (category <> ''),
	UNIQUE(tenant_id, category)
);
CREATE INDEX IF NOT EXISTS category_tenant_id_idx ON category (tenant_id);

CREATE TABLE IF NOT EXISTS product (
    tenant_id UUID NOT NULL,
	product_id SERIAL PRIMARY KEY,
	sku VARCHAR(32) NOT NULL CHECK (sku <> ''),
	product VARCHAR(255) NOT NULL CHECK (product <> ''),
	price DECIMAL(9,2) NOT NULL,
	UNIQUE(tenant_id, sku)
);
CREATE INDEX IF NOT EXISTS product_tenant_id_idx ON product (tenant_id);

CREATE TABLE IF NOT EXISTS product_categories (
	product_id INT NOT NULL REFERENCES product (product_id) ON DELETE CASCADE ON UPDATE CASCADE,
	category_id INT NOT NULL REFERENCES category (category_id) ON DELETE RESTRICT ON UPDATE CASCADE,
	CONSTRAINT product_categories_pk PRIMARY KEY (product_id, category_id)
);
