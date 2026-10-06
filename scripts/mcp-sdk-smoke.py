"""Interoperability smoke using the official MCP Python SDK. Public reads only."""
import argparse
import asyncio
import json
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client


async def verify(endpoint, keyword):
    async with streamablehttp_client(endpoint, timeout=20) as (read, write, _):
        async with ClientSession(read, write) as session:
            initialized = await session.initialize()
            listed = await session.list_tools()
            names = [tool.name for tool in listed.tools]
            assert "search_products" in names, "Public catalog tool is absent"
            assert not set(names) & {"place_order", "cancel_order", "create_after_sale"}, "Direct write tools must not be exposed"
            result = await session.call_tool("search_products", {"keyword": keyword, "page": 1, "pageSize": 2})
            assert not result.isError, "Public catalog search failed"
            print(json.dumps({"protocolVersion": initialized.protocolVersion, "tools": names,
                              "publicSearchSucceeded": True}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="http://localhost:8080/agent/customer/mcp")
    parser.add_argument("--keyword", default="手机")
    args = parser.parse_args()
    asyncio.run(verify(args.endpoint, args.keyword))
