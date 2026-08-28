package xray

import (
	"context"
	"errors"
	"fmt"
	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/wrapperspb"
	"net"
	"os"
	"path/filepath"
	"testing"
)

type constructionFeature struct {
	listener   net.Listener
	failClose  bool
	closeCalls int
}

func (*constructionFeature) Type() interface{} { return (*constructionFeature)(nil) }
func (*constructionFeature) Start() error      { return nil }
func (f *constructionFeature) Close() error {
	f.closeCalls++
	if f.failClose {
		return errors.New("controlled construction cleanup failure")
	}
	return f.listener.Close()
}

var constructingFeature *constructionFeature

func init() {
	common.Must(common.RegisterConfig((*wrapperspb.StringValue)(nil), func(_ context.Context, config interface{}) (interface{}, error) {
		if config.(*wrapperspb.StringValue).Value == "fail" {
			return nil, errors.New("controlled later factory failure")
		}
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
		constructingFeature.listener = listener
		return constructingFeature, nil
	}))
}

func TestManagedConstructionFailureRetainsResources(t *testing.T) {
	constructors := map[string]func(*core.Config) (*core.Instance, error){
		"New": core.New,
		"NewWithContext": func(config *core.Config) (*core.Instance, error) {
			return core.NewWithContext(context.Background(), config)
		},
		"StartInstance": func(config *core.Config) (*core.Instance, error) {
			payload, err := proto.Marshal(config)
			if err != nil {
				return nil, err
			}
			return core.StartInstance("protobuf", payload)
		},
	}
	for name, construct := range constructors {
		for _, failClose := range []bool{false, true} {
			t.Run(fmt.Sprint(name, "/", failClose), func(t *testing.T) {
				feature := &constructionFeature{failClose: failClose}
				constructingFeature = feature
				t.Cleanup(func() {
					if feature.listener != nil {
						feature.listener.Close()
					}
					coreServer = nil
					nativeStopFailure = nil
					constructingFeature = nil
				})
				config := &core.Config{App: []*serial.TypedMessage{
					serial.ToTypedMessage(wrapperspb.String("resource")),
					serial.ToTypedMessage(wrapperspb.String("fail")),
				}}
				instance, err := construct(config)
				if err == nil {
					t.Fatal("later factory failure was ignored")
				}
				if instance == nil {
					t.Fatal("construction discarded earlier feature ownership")
				}
				if feature.listener == nil {
					t.Fatal("first factory did not open its real listener")
				}
				coreServer = instance
				if err := StopXray(); failClose {
					if err == nil || coreServer != instance {
						t.Fatal("failed construction cleanup lost owner")
					}
					if StopXray() == nil || feature.closeCalls != 1 {
						t.Fatal("construction cleanup failure did not remain sticky")
					}
					if rebound, err := net.Listen("tcp", feature.listener.Addr().String()); err == nil {
						rebound.Close()
						t.Fatal("failed close unexpectedly released the real listener")
					}
				} else {
					if err != nil || coreServer != nil {
						t.Fatal("clean construction cleanup did not release owner", err)
					}
					rebound, err := net.Listen("tcp", feature.listener.Addr().String())
					if err != nil {
						t.Fatal("earlier real listener was not closed", err)
					}
					rebound.Close()
				}
			})
		}
	}
}

func TestManagedJSONConstructionFailureRetainsOwner(t *testing.T) {
	config := fmt.Sprintf(`{"log":{"loglevel":"debug","error":%q}}`, filepath.Join(t.TempDir(), "missing", "runtime.log"))
	configPath := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(configPath, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	for name, run := range map[string]func() error{
		"JSON": func() error { return RunXrayFromJSON(t.TempDir(), "", config) },
		"file": func() error { return RunXray(t.TempDir(), "", configPath) },
	} {
		t.Run(name, func(t *testing.T) {
			if err := run(); err == nil {
				t.Fatal("invalid log directory was accepted")
			}
			defer StopXray()
			if coreServer == nil {
				t.Fatal("JSON construction failure discarded the partial core instance")
			}
			if err := StopXray(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestManagedPartialStartRetainsOwner(t *testing.T) {
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	config := fmt.Sprintf(`{"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks"}],"outbounds":[{"protocol":"freedom"}]}`, occupied.Addr().(*net.TCPAddr).Port)
	if err := RunXrayFromJSON(t.TempDir(), "", config); err == nil {
		t.Fatal("occupied inbound was accepted")
	}
	defer StopXray()
	if coreServer == nil {
		t.Fatal("partial Start failure discarded native owner before cleanup")
	}
	if err := StopXray(); err != nil {
		t.Fatal(err)
	}
	if coreServer != nil {
		t.Fatal("clean Stop did not release owner")
	}
	occupied.Close()
	if err := RunXrayFromJSON(t.TempDir(), "", config); err != nil {
		t.Fatal(err)
	}
	if !GetXrayState() {
		t.Fatal("real restart did not reach running")
	}
	if err := StopXray(); err != nil {
		t.Fatal(err)
	}
	rebound, err := net.Listen("tcp", occupied.Addr().String())
	if err != nil {
		t.Fatal("real stop did not release inbound:", err)
	}
	rebound.Close()
}

type failCloseFeature struct{ calls int }

func (*failCloseFeature) Type() interface{} { return (*failCloseFeature)(nil) }
func (*failCloseFeature) Start() error      { return nil }
func (f *failCloseFeature) Close() error {
	f.calls++
	if f.calls == 1 {
		return errors.New("controlled close failure")
	}
	return nil
}

func TestManagedCloseFailureRetainsOwner(t *testing.T) {
	instance, err := core.New(&core.Config{})
	if err != nil {
		t.Fatal(err)
	}
	feature := &failCloseFeature{}
	t.Cleanup(func() {
		// The fixture feature owns no OS resources. Only a fresh process resets production failure.
		coreServer = nil
		nativeStopFailure = nil
	})
	if err := instance.AddFeature(feature); err != nil {
		t.Fatal(err)
	}
	coreServer = instance
	defer StopXray()
	if err := StopXray(); err == nil {
		t.Fatal("expected close failure")
	}
	if coreServer != instance {
		t.Fatal("failed Close discarded the only native cleanup owner")
	}
	if err := StopXray(); err == nil {
		t.Fatal("a repeated Stop hid the observed native Close failure")
	}
	if feature.calls != 1 || coreServer != instance {
		t.Fatal("failed cleanup must stay owned until process death")
	}
}

func TestManagedStartDoesNotReplaceOwner(t *testing.T) {
	instance, err := core.New(&core.Config{})
	if err != nil {
		t.Fatal(err)
	}
	coreServer = instance
	defer StopXray()
	if err := RunXrayFromJSON(t.TempDir(), "", "{}"); err == nil {
		t.Fatal("overlapping native start accepted")
	}
	if coreServer != instance {
		t.Fatal("overlapping start replaced existing cleanup owner")
	}
}
