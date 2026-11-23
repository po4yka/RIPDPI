-- Standard Lua strategies for existing sockets.
-- Selected and adapted from zapret2 (MIT, see LICENSE.zapret2.txt).

function pass(ctx, desync)
	DLOG("pass")
end

function instance_cutoff_shim(ctx, desync, dir)
	if ctx then
		instance_cutoff(ctx, dir)
	elseif not desync.track then
		DLOG("instance_cutoff_shim: cannot cutoff '"..desync.func_instance.."' because conntrack is absent")
	else
		if not desync.track.lua_state.cutoff_shim then
			desync.track.lua_state.cutoff_shim = {}
		end
		if not desync.track.lua_state.cutoff_shim[desync.func_instance] then
			desync.track.lua_state.cutoff_shim[desync.func_instance] = {}
		end
		if type(dir)=="nil" then
			-- cutoff both directions by default
			desync.track.lua_state.cutoff_shim[desync.func_instance][true] = true
			desync.track.lua_state.cutoff_shim[desync.func_instance][false] = true
		else
			desync.track.lua_state.cutoff_shim[desync.func_instance][dir] = true
		end
		if b_debug then DLOG("instance_cutoff_shim: cutoff '"..desync.func_instance.."' in="..tostring(type(dir)=="nil" and true or not dir).." out="..tostring(type(dir)=="nil" or dir)) end
	end
end

function cutoff_shim_check(desync)
	if not desync.track then
		DLOG("cutoff_shim_check: cannot check '"..desync.func_instance.."' cutoff because conntrack is absent")
		return false
	else
		local b=desync.track.lua_state.cutoff_shim and
			desync.track.lua_state.cutoff_shim[desync.func_instance] and
			desync.track.lua_state.cutoff_shim[desync.func_instance][desync.outgoing]
		if b and b_debug then
			DLOG("cutoff_shim_check: '"..desync.func_instance.."' "..(desync.outgoing and "out" or "in").." cutoff")
		end
		return b
	end
end

function barray(a, packer)
	if a then
		local sa={}
		for i=1,#a do
			sa[i] = packer(a[i])
		end
		return table.concat(sa)
	end
end

function deepcopy(orig, copies)
    copies = copies or {}
    local orig_type = type(orig)
    local copy
    if orig_type == 'table' then
        if copies[orig] then
            copy = copies[orig]
        else
            copy = {}
            copies[orig] = copy
            for orig_key, orig_value in next, orig, nil do
                copy[deepcopy(orig_key, copies)] = deepcopy(orig_value, copies)
            end
            setmetatable(copy, deepcopy(getmetatable(orig), copies))
        end
    else -- number, string, boolean, etc
        copy = orig
    end
    return copy
end

function in_list(s, v)
	if s then
		for elem in string.gmatch(s, "[^,]+") do
			if elem==v then
				return true
			end
		end
	end
	return false
end

function blob_exist(desync, name)
	return name and (string.sub(name,1,2)=="0x" or _G[name] or desync[name])
end

function blob(desync, name, def)
	if not name or #name==0 then
		if def then
			return def
		else
			error("empty blob name")
		end
	end
	local blob
	if string.sub(name,1,2)=="0x" then
		blob = parse_hex(string.sub(name,3))
		if not blob then
			error("invalid hex string : "..name)
		end
	else
		blob = desync[name]
		if not blob then
			-- use global var if no field in dissect table
			blob = _G[name]
			if not blob then
				error("blob  '"..name.."' unavailable")
			end
		end
		blob = tostring(blob)
	end
	return blob
end

function blob_or_def(desync, name, def)
	return name and blob(desync,name,def) or def
end

function pattern(pat, offset, len)
	if not pat or #pat==0 then
		error("pattern: bad or empty pattern")
	end
	local off = (offset-1) % #pat
	local pats = divint((len + #pat - 1), #pat) + (off==0 and 0 or 1)
	return string.sub(string.rep(pat,pats),off+1,off+len)
end

function delete_pos_1(a)
	local i=1
	while i<=#a do
		if type(a[i])=="number" and a[i] == 1 then
			table.remove(a,i)
		else
			i = i+1
		end
	end
	return a
end

function array_field_search(a, f, v)
	for k,val in pairs(a) do
		if type(val)=="table" and val[f]==v then
			return k
		end
	end
end

function find_next_line(s, pos)
	local p1, p2
	p1 = string.find(s,"[\r\n]",pos)
	if p1 then
		p2 = p1
		p1 = p1-1
		if string.sub(s,p2,p2)=='\r' then p2=p2+1 end
		if string.sub(s,p2,p2)=='\n' then p2=p2+1 end
		if p2>#s then p2=nil end
	else
		p1 = #s
	end
	return p1,p2
end

function direction_check(desync, def)
	local dir = desync.arg.dir or def or "out"
	return desync.outgoing and dir~="in" or not desync.outgoing and dir~="out"
end

function direction_cutoff_opposite(ctx, desync, def)
	local dir = desync.arg.dir or def or "out"
	if dir=="out" then
		-- cutoff in
		instance_cutoff_shim(ctx, desync, false)
	elseif dir=="in" then
		-- cutoff out
		instance_cutoff_shim(ctx, desync, true)
	end
end

function payload_match_filter(l7payload, l7payload_filter, def)
	local argpl = l7payload_filter or def or "known"
	local neg = string.sub(argpl,1,1)=="~"
	local pl = neg and string.sub(argpl,2) or argpl
	return neg ~= (in_list(pl, "all") or in_list(pl, l7payload) or in_list(pl, "known") and l7payload~="unknown" and l7payload~="empty")
end

function payload_check(desync, def)
	local b = payload_match_filter(desync.l7payload, desync.arg.payload, def)
	if not b and b_debug then
		local argpl = desync.arg.payload or def or "known"
		DLOG("payload_check: payload '"..desync.l7payload.."' does not pass '"..argpl.."' filter")
	end
	return b
end

function http_dissect_header(header)
	local p1,p2
	p1,p2 = string.find(header,":")
	if p1 then
		p2=string.find(header,"[^ \t]",p2+1)
		return string.sub(header,1,p1-1), p2 and string.sub(header,p2) or "", p1-1, p2 or #header
	end
	return nil
end

function http_dissect_headers(http, pos)
	local eol,pnext,header,value,headers,pos_endheader,pos_startvalue,pos_headers_end
	headers={}
	while pos do
		eol,pnext = find_next_line(http,pos)
		header = string.sub(http,pos,eol)
		if #header == 0 then
			local ending = string.sub(http,eol+1,eol+2)
			if ending == "\r\n" or string.sub(ending,1,1) == "\n" then
				pos_headers_end = pnext or (#http + 1)
			end
			break
		end
		header,value,pos_endheader,pos_startvalue = http_dissect_header(header)
		if header then
			headers[#headers+1] = { header_low = string.lower(header), header = header, value = value, pos_start = pos, pos_end = eol, pos_header_end = pos+pos_endheader-1, pos_value_start = pos+pos_startvalue-1 }
		end
		pos=pnext
	end
	return headers, pos_headers_end
end

function http_dissect_req(http)
	if not http then return nil; end
	local eol,pnext,req,hdrpos
	local pos=1
	-- skip methodeol empty line(s)
	while pos do
		eol,pnext = find_next_line(http,pos)
		req = string.sub(http,pos,eol)
		pos=pnext
		if #req>0 then break end
	end
	hdrpos = pos
	if not req or #req==0 then return nil end
	pos = string.find(req,"[ \t]")
	if not pos then return nil end
	local method = string.sub(req,1,pos-1);
	pos = string.find(req,"[^ \t]",pos+1)
	if not pos then return nil end
	pnext = string.find(req,"[ \t]",pos+1)
	if not pnext then pnext = #req + 1 end
	local uri = string.sub(req,pos,pnext-1)
	pos = string.find(req,"[^ \t]",pnext)
	local http_ver
	if pos then
		pnext = string.find(req,"[\r\n]",pos)
		if not pnext then pnext = #req + 1 end
		http_ver = string.sub(req,pos,pnext-1)
	end
	local hdis = { method = method, uri = uri, http_ver = http_ver }
	hdis.headers, hdis.pos_headers_end = http_dissect_headers(http,hdrpos)
	if hdis.pos_headers_end then
		hdis.body = string.sub(http, hdis.pos_headers_end)
	end
	return hdis
end

function http_dissect_reply(http)
	if not http then return nil; end
	local s, pos, code
	s = string.sub(http,1,8)
	if s~="HTTP/1.1" and s~="HTTP/1.0" then return nil end
	pos = string.find(http,"[ \t\r\n]",10)
	if not pos then return nil end
	code = tonumber(string.sub(http,10,pos-1))
	if not code then return nil end
	s,pos = find_next_line(http,pos)
	local hdis = { code = code }
	hdis.headers, hdis.pos_headers_end = http_dissect_headers(http,pos)
	if hdis.pos_headers_end then
		hdis.body = string.sub(http, hdis.pos_headers_end)
	end
	return hdis
end

function http_reconstruct_headers(headers, unixeol)
	local eol = unixeol and "\n" or "\r\n"
	return headers and barray(headers, function(a) return a.header..": "..a.value..eol end) or ""
end

function http_reconstruct_req(hdis, unixeol)
	local eol = unixeol and "\n" or "\r\n"
	return hdis.method.." "..hdis.uri..(hdis.http_ver and (" "..hdis.http_ver) or "")..eol..http_reconstruct_headers(hdis.headers, unixeol)..eol..(hdis.body or "")
end
